package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.HashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.conversation.ConversationService;
import com.tranche.bakery.flow.actions.SavePaymentScreenshotAction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;

import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderChannel;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;

/** Real local PostgreSQL transactions, following the existing flow fixture (no Docker/provider). */
@TestPropertySource(properties = {
    "bakery.payment.provider=UPI_QR",
    "bakery.payment.razorpay.key-id=",
    "bakery.payment.razorpay.key-secret="
})
class CapacityBoundaryTest extends FlowScenarioBase {

    @Autowired private CapacityLock capacityLock;
    @Autowired private OrderService orderService;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private DeliveryRules rules;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ConversationService conversationService;
    @Autowired private SavePaymentScreenshotAction screenshots;

    @Test
    void lockRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> capacityLock.acquire())
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void advisoryWaitIsBoundedAndDraftEditsDoNotTakeGlobalCapacity() throws Exception {
        Order draft = order(friday(), 1, OrderStatus.DRAFT, "Classic Table White");
        Long menuId = orderItems.findAllByOrderId(draft.getId()).get(0).getMenuItem().getId();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                    .executeWithoutResult(tx -> {
                        capacityLock.acquire();
                        acquired.countDown();
                        await(release);
                    }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            orderService.addItem(draft, menuId, 1);
            assertThat(orderItems.sumQuantityByOrderId(draft.getId())).isEqualTo(2);
            assertThatThrownBy(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                    .executeWithoutResult(tx -> capacityLock.acquire()))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void confirmRechecksFreshCapacityAndRollsBackWithoutChangingDraft() {
        LocalDate date = friday();
        Order reserved = order(date, rules.getDailyCapacity(), OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        Order draft = order(date, 1, OrderStatus.DRAFT, "Classic Table White");

        assertThatThrownBy(() -> orderService.confirm(draft)).hasMessage("FULLY_BOOKED");
        assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
        assertThat(rules.bookedQuantity(date)).isEqualTo(rules.getDailyCapacity());
        // Self is excluded: reconfirming a full existing reservation is not double-counted.
        orderService.confirm(reserved);
        assertOrderStatus(reserved.getId(), OrderStatus.PENDING_CONFIRMATION);
    }

    @Test
    void pendingGrowthAndRevivalRecheckCapacityAndStaleRepricingCannotResurrect() {
        LocalDate date = friday();
        Order reserved = order(date, rules.getDailyCapacity(), OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        Long menuId = orderItems.findAllByOrderId(reserved.getId()).get(0).getMenuItem().getId();
        assertThatThrownBy(() -> orderService.addItem(reserved, menuId, 1)).hasMessage("FULLY_BOOKED");
        assertThat(orderItems.sumQuantityByOrderId(reserved.getId())).isEqualTo(rules.getDailyCapacity());
        Order cancelled = order(date, 1, OrderStatus.CANCELLED, "Classic Table White");
        jdbcTemplate.update("UPDATE orders SET cutoff_cancelled = true WHERE id = ?", cancelled.getId());
        assertThatThrownBy(() -> orderService.reviveCutoffCancelled(cancelled.getId(), customer.getId(), date))
                .hasMessage("FULLY_BOOKED");
        assertOrderStatus(cancelled.getId(), OrderStatus.CANCELLED);

        orderService.cancel(reserved);
        assertThatThrownBy(() -> orderService.recalculate(reserved)).hasMessage("INVALID_ORDER");
        assertOrderStatus(reserved.getId(), OrderStatus.CANCELLED);
        orderService.reviveCutoffCancelled(cancelled.getId(), customer.getId(), date);
        assertOrderStatus(cancelled.getId(), OrderStatus.PENDING_CONFIRMATION);
    }

    @Test
    void confirmReadsCurrentPersistedQuantitiesRatherThanEarlierCartSnapshot() {
        Order draft = order(friday(), 1, OrderStatus.DRAFT, "Classic Table White");
        jdbcTemplate.update("UPDATE order_items SET quantity = ? WHERE order_id = ?",
                rules.getDailyCapacity() + 1, draft.getId());
        assertThatThrownBy(() -> orderService.confirm(draft)).hasMessage("FULLY_BOOKED");
        assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
    }

    @Test
    void confirmRejectsWednesdayWeekdayFocacciaAndInsufficientBagelNotice() {
        Order closed = order(friday().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY)),
                1, OrderStatus.DRAFT, "Classic Table White");
        assertThatThrownBy(() -> orderService.confirm(closed)).hasMessage("INVALID_DELIVERY_DATE");
        orderService.cancel(closed);
        Order weekend = order(friday().with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
                1, OrderStatus.DRAFT, "Olive, Tomato & Rosemary Focaccia");
        assertThatThrownBy(() -> orderService.confirm(weekend)).hasMessage("INVALID_DELIVERY_DATE");
        orderService.cancel(weekend);
        LocalDate tooSoon = rules.earliestDate(new DeliveryRules.CartFlags(true, false, 1, false)).minusDays(1);
        Order bagel = order(tooSoon, 1, OrderStatus.DRAFT, "Classic New York-Style Bagel");
        assertThatThrownBy(() -> orderService.confirm(bagel)).hasMessage("INVALID_DELIVERY_DATE");
    }

    @Test
    void whatsappCapacityRejectionRollsBackBeforeRecoveringWithoutPayment() {
        LocalDate date = friday();
        order(date, rules.getDailyCapacity(), OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        Order draft = order(date, 1, OrderStatus.DRAFT, "Classic Table White");
        prepareConversation("ORDER_CONFIRM", draft);
        var message = new ObjectMapper().createObjectNode();
        message.putObject("text").put("body", "confirm");

        // Real inbound boundary, not just a direct OrderService call: no UnexpectedRollbackException.
        conversationService.handle(customer, "text", message);
        reloadConversation();

        assertState("ORDER_SELECT_DATE");
        assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
        assertThat(orderItems.sumQuantityByOrderId(draft.getId())).isEqualTo(1);
        assertThat(orderRepository.findById(Objects.requireNonNull(draft.getId())).orElseThrow().getOrderNumber()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
        assertThat(sentTexts).anyMatch(text -> text.contains("Your cart is unchanged"));
    }

    @Test
    void emptyDraftCannotAdvanceToPaymentThroughSwallowedActionFailure() {
        Order empty = orderService.getOrCreateDraft(customer, conversation);
        prepareConversation("ORDER_CONFIRM", empty);

        send("confirm");

        assertState("ORDER_CONFIRM");
        assertOrderStatus(empty.getId(), OrderStatus.DRAFT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
        assertThat(sentTexts).anyMatch(text -> text.contains("No payment was created"));
    }

    @Test
    void failedLatePaymentRevivalRollsBackDateAndPreservesScreenshotForRetry() {
        LocalDate date = friday();
        LocalDate originalDate = date.minusDays(7);
        order(date, rules.getDailyCapacity(), OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        Order cancelled = order(originalDate, 1, OrderStatus.CANCELLED, "Classic Table White");
        jdbcTemplate.update("UPDATE orders SET cutoff_cancelled = true WHERE id = ?", cancelled.getId());
        prepareConversation("LATE_PAYMENT_DATE", cancelled);
        conversation.getContext().put("lateScreenshotMediaId", "retain-for-retry");
        conversation = conversationRepository.save(Objects.requireNonNull(conversation));

        send(date.toString());

        assertState("LATE_PAYMENT_DATE");
        Order unchanged = orderRepository.findById(Objects.requireNonNull(cancelled.getId())).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(unchanged.getDeliveryDate()).isEqualTo(originalDate);
        assertThat(unchanged.isCutoffCancelled()).isTrue();
        assertThat(conversation.getContext()).containsEntry("lateScreenshotMediaId", "retain-for-retry");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
    }

    @Test
    void whatsappLockTimeoutRollsBackAndReturnsRetryWithoutPayment() throws Exception {
        Order draft = order(friday(), 1, OrderStatus.DRAFT, "Classic Table White");
        prepareConversation("ORDER_CONFIRM", draft);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                    .executeWithoutResult(tx -> {
                        capacityLock.acquire();
                        acquired.countDown();
                        await(release);
                    }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            send("confirm");
            assertState("ORDER_CONFIRM");
            assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
            assertThat(sentTexts).anyMatch(text -> text.contains("Please try again"));
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void draftMutationEscalationNeverWaitsForCapacityAndRollsBackItsEdits() throws Exception {
        Order draft = order(friday(), 1, OrderStatus.DRAFT, "Classic Table White");
        Long menuId = orderItems.findAllByOrderId(draft.getId()).get(0).getMenuItem().getId();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                    .executeWithoutResult(tx -> {
                        capacityLock.acquire();
                        acquired.countDown();
                        await(release);
                    }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                    .executeWithoutResult(tx -> {
                        orderService.addItem(draft, menuId, 1);
                        orderService.confirm(draft);
                    }))
                    .isInstanceOf(PessimisticLockingFailureException.class)
                    .hasMessageContaining("Capacity changed during draft mutation");
            assertThat(orderItems.sumQuantityByOrderId(draft.getId())).isEqualTo(1);
            assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void whatsappDraftRowContentionDoesNotAdvanceOrCrashTransaction() throws Exception {
        Order draft = order(friday(), 1, OrderStatus.DRAFT, "Classic Table White");
        prepareConversation("ORDER_CONFIRM", draft);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                    .executeWithoutResult(tx -> {
                        jdbcTemplate.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE",
                                Long.class, draft.getId());
                        acquired.countDown();
                        await(release);
                    }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            send("hi"); // greeting cancels a draft using NOWAIT; it must roll back, not continue.
            assertState("ORDER_CONFIRM");
            assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
            assertThat(sentTexts).anyMatch(text -> text.contains("Please try again"));
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void webOnlyPendingOrderCannotReceiveScreenshotOrAutoConfirmFromStaleContext() {
        Order web = order(friday(), 1, OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        jdbcTemplate.update("UPDATE orders SET channel = 'WEB' WHERE id = ?", web.getId());
        prepareConversation("PAYMENT_PENDING", web);

        sendImage("web-must-not-receive-this");

        assertState("IDLE");
        assertOrderStatus(web.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(orderService.findAwaitingPayment(customer.getId())).isEmpty();
        assertThat(orderService.hasPendingPayment(customer.getId())).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_screenshots", Long.class)).isZero();
        web = orderRepository.findById(Objects.requireNonNull(web.getId())).orElseThrow();
        screenshots.applyScreenshot("direct-helper-must-also-refuse", web);
        assertOrderStatus(web.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
    }

    @Test
    void screenshotForMixedChannelPendingOrdersAppliesOnlyToWhatsappOrder() {
        Order web = order(friday(), 1, OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        jdbcTemplate.update("UPDATE orders SET channel = 'WEB' WHERE id = ?", web.getId());
        Order legacy = order(friday(), 1, OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        prepareConversation("PAYMENT_PENDING", web);

        sendImage("legacy-only");

        assertOrderStatus(web.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertOrderStatus(legacy.getId(), OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
        assertThat(jdbcTemplate.queryForObject("SELECT order_id FROM payments", Long.class)).isEqualTo(legacy.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_screenshots", Long.class)).isEqualTo(1L);
    }

    @Test
    void forgedScreenshotSelectionCannotTargetWebOrder() {
        Order web = order(friday(), 1, OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        jdbcTemplate.update("UPDATE orders SET channel = 'WEB' WHERE id = ?", web.getId());
        Order legacy = order(friday(), 1, OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        prepareConversation("PAYMENT_ORDER_SELECT", legacy);
        conversation.getContext().put("pendingScreenshotMediaId", "forged-selection");
        conversation = conversationRepository.save(Objects.requireNonNull(conversation));

        send("pay_" + web.getId());

        assertState("PAYMENT_ORDER_SELECT");
        assertOrderStatus(web.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertOrderStatus(legacy.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
        assertThat(sentButtonTitles).hasSize(1);
    }

    @Test
    void pendingMergeIsCapacityCheckedAndWebSnapshotCannotBeMutated() {
        LocalDate date = friday();
        Order reserved = order(date, rules.getDailyCapacity(), OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        Order draft = order(date, 1, OrderStatus.DRAFT, "Classic Table White");
        assertThatThrownBy(() -> orderService.resolveCart(customer, draft, date)).hasMessage("FULLY_BOOKED");
        assertThat(orderItems.sumQuantityByOrderId(reserved.getId())).isEqualTo(rules.getDailyCapacity());
        assertOrderStatus(draft.getId(), OrderStatus.DRAFT);

        jdbcTemplate.update("UPDATE orders SET channel = 'WEB' WHERE id = ?", reserved.getId());
        Order web = orderRepository.findById(Objects.requireNonNull(reserved.getId())).orElseThrow();
        assertThat(orderService.resolveCart(customer, draft, date).order().getId()).isEqualTo(draft.getId());
        assertThatThrownBy(() -> orderService.mergeItems(draft, web)).hasMessage("WEB_ORDER_IMMUTABLE");
        assertThatThrownBy(() -> orderService.addItem(web,
                orderItems.findAllByOrderId(web.getId()).get(0).getMenuItem().getId(), 1))
                .hasMessage("WEB_ORDER_IMMUTABLE");
        assertThat(orderItems.sumQuantityByOrderId(web.getId())).isEqualTo(rules.getDailyCapacity());
        assertThat(web.getChannel()).isEqualTo(OrderChannel.WEB);
    }

    @Test
    void concurrentConfirmsCannotBothClaimLastSlot() throws Exception {
        LocalDate date = friday();
        order(date, rules.getDailyCapacity() - 1, OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
        Order first = order(date, 1, OrderStatus.DRAFT, "Classic Table White");
        var otherCustomer = new com.tranche.bakery.customer.Customer();
        otherCustomer.setPhone("919000000002");
        otherCustomer = customerRepository.save(otherCustomer);
        Order second = order(date, 1, OrderStatus.DRAFT, "Classic Table White", otherCustomer);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> confirmAfterBarrier(first, ready, start));
            var b = executor.submit(() -> confirmAfterBarrier(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "FULLY_BOOKED");
            assertThat(rules.bookedQuantity(date)).isEqualTo(rules.getDailyCapacity());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void legacyConfirmWaitsForSharedReservationCommitAndThenSeesIt() throws Exception {
        LocalDate date = friday();
        Order draft = order(date, 1, OrderStatus.DRAFT, "Classic Table White");
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch confirming = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var reservation = executor.submit(() -> new TransactionTemplate(Objects.requireNonNull(transactions)).executeWithoutResult(tx -> {
                capacityLock.acquire();
                order(date, rules.getDailyCapacity(), OrderStatus.PENDING_CONFIRMATION, "Classic Table White");
                acquired.countDown();
                await(commit);
            }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            var legacy = executor.submit(() -> {
                confirming.countDown();
                return confirmResult(draft);
            });
            assertThat(confirming.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(legacy.isDone()).isFalse();
            commit.countDown();
            reservation.get(15, TimeUnit.SECONDS);
            assertThat(legacy.get(15, TimeUnit.SECONDS)).isEqualTo("FULLY_BOOKED");
            assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    private String confirmAfterBarrier(Order order, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return confirmResult(order);
    }

    private String confirmResult(Order order) {
        try {
            orderService.confirm(order);
            return "OK";
        } catch (IllegalStateException ex) {
            return ex.getMessage();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private LocalDate friday() {
        return LocalDate.now().plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    private void prepareConversation(String state, Order order) {
        conversation.setState(state);
        conversation.setContext(new HashMap<>());
        conversation.getContext().put("orderId", order.getId().toString());
        conversation = conversationRepository.save(Objects.requireNonNull(conversation));
    }

    private Order order(LocalDate date, int quantity, OrderStatus status, String itemName) {
        return order(date, quantity, status, itemName, customer);
    }

    private Order order(LocalDate date, int quantity, OrderStatus status, String itemName,
                        com.tranche.bakery.customer.Customer owner) {
        Order order = new Order();
        order.setCustomer(owner);
        order.setDeliveryDate(date);
        order.setStatus(status);
        order.setTotalAmount(BigDecimal.ZERO);
        order = orderRepository.save(order);
        var menu = itemRepository.findFirstByNameAndActiveTrue(itemName).orElseThrow();
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setMenuItem(menu);
        item.setQuantity(quantity);
        item.setUnitPrice(menu.getPrice());
        item.setSubtotal(menu.getPrice().multiply(BigDecimal.valueOf(quantity)));
        orderItems.save(item);
        return order;
    }
}