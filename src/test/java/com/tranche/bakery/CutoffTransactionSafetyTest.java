package com.tranche.bakery;

import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.order.CutoffJob;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderChannel;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.whatsapp.CustomerNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** All provider/notification boundaries are mocks; uses only the local test database. */
class CutoffTransactionSafetyTest extends FlowScenarioBase {
    @Autowired CutoffJob cutoff;
    @Autowired AdminService admin;
    @Autowired PaymentRepository payments;
    @Autowired OrderItemRepository orderItems;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean CapacityLock capacityLock;
    @MockitoBean RazorpayService gateway;
    @MockitoBean CustomerNotifier notifier;
    @MockitoBean AlertService alerts;

    @BeforeEach
    void mockNetworkBoundaries() throws Exception {
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchPaymentLink(anyString())).thenAnswer(invocation -> {
            outsideTransaction();
            return new RazorpayService.LinkState("created", BigDecimal.ZERO, null);
        });
        doAnswer(invocation -> {
            outsideTransaction();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return null;
        })
                .when(gateway).cancelPaymentLink(anyString());
        doAnswer(invocation -> { outsideTransaction(); return null; })
                .when(notifier).orderConfirmed(any());
        doAnswer(invocation -> { outsideTransaction(); return null; })
                .when(notifier).orderCancelled(any(), any());
        doAnswer(invocation -> { outsideTransaction(); return null; })
                .when(alerts).raise(anyString(), anyString(), any(), any());
    }

    @Test
    void unpaidCancellationFetchRevokeAndNotificationAreOutsideTransactions() throws Exception {
        Order order = pending();
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reload(order).isCutoffCancelled()).isTrue();
        assertThat(payment(order).getGatewayLinkId()).isNull();
        verify(gateway).cancelPaymentLink("plink_saved");
        verify(notifier).orderCancelled(any(), any());
    }

    @Test
    void paidLinkUsesExistingConfirmationAndNotifiesAfterCommit() throws Exception {
        Order order = pending();
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            return new RazorpayService.LinkState("paid", new BigDecimal("400"), "pay_saved");
        });
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
        verify(notifier).orderConfirmed(any());
        verify(alerts).raise(eq("ORDER_PAID"), anyString(), any(), any());
        verify(gateway, never()).cancelPaymentLink(anyString());
    }

    @Test
    void regularGatewayConfirmationCommitsAndReleasesCapacityBeforeNotifications() {
        Order order = pending();
        observeCommittedNotifications(order);
        new TransactionTemplate(Objects.requireNonNull(transactionManager)).executeWithoutResult(status -> {
            admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()), "pay_saved",
                    "plink_saved", new BigDecimal("400"));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly(); // the independent settlement must already be durable
        });
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(notifier).orderConfirmed(any());
        verify(alerts).raise(eq("ORDER_PAID"), anyString(), any(), any());
    }

    @Test
    void signedWebGatewayConfirmationCommitsAndReleasesCapacityBeforeNotifications() {
        Order order = pendingWeb();
        observeCommittedNotifications(order);
        new TransactionTemplate(Objects.requireNonNull(transactionManager)).executeWithoutResult(status -> {
            admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()), "pay_saved",
                    "plink_saved", new BigDecimal("400"), "notification_attempt",
                    "notification_reference", "INR");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
        verify(notifier).orderConfirmed(any());
        verify(alerts).raise(eq("ORDER_PAID"), anyString(), any(), any());
    }

    @Test
    void regularGatewaySettlementRollbackSendsNoNotifications() {
        Order order = pending();
        failSettlementAtCommit();
        assertThatThrownBy(() -> admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()),
                "pay_saved", "plink_saved", new BigDecimal("400")))
                .isInstanceOf(IllegalStateException.class).hasMessage("mock settlement commit failure");
        assertRolledBackWithoutNotifications(order);
    }

    @Test
    void signedWebGatewaySettlementRollbackSendsNoNotifications() {
        Order order = pendingWeb();
        failSettlementAtCommit();
        assertThatThrownBy(() -> admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()),
                "pay_saved", "plink_saved", new BigDecimal("400"), "notification_attempt",
                "notification_reference", "INR"))
                .isInstanceOf(IllegalStateException.class).hasMessage("mock settlement commit failure");
        assertRolledBackWithoutNotifications(order);
    }

    @Test
    void webReceiptContextSurvivesRepositoryTransactionsAndSubsequentAdminAlert() {
        Order order = pendingWeb();
        observeReceiptPersistenceContext(order);
        admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()), "pay_saved",
                "plink_saved", new BigDecimal("400"), "notification_attempt",
                "notification_reference", "INR");
        assertReceiptEffectsCleanedUp();
    }

    @Test
    void cutoffReceiptContextSurvivesRepositoryTransactionsAndSubsequentAdminAlert() throws Exception {
        Order order = pending();
        observeReceiptPersistenceContext(order);
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            return new RazorpayService.LinkState("paid", new BigDecimal("400"), "pay_saved");
        });
        cutoff.cancelUnfinishedOrders();
        assertReceiptEffectsCleanedUp();
    }

    @Test
    void replacedLinkDuringFetchIsNotTrustedOrRevoked() throws Exception {
        Order order = pending();
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            Payment payment = payment(order);
            payment.setGatewayLinkId("plink_replacement");
            payments.save(payment);
            return new RazorpayService.LinkState("paid", new BigDecimal("400"), "pay_stale");
        });
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(payment(order).getGatewayLinkId()).isEqualTo("plink_replacement");
        assertThat(payment(order).getGatewayPaymentId()).isNull();
        verify(gateway, never()).cancelPaymentLink(anyString());
    }

    @Test
    void partialPaymentIsHeldAndAlertedOnlyAfterCommit() throws Exception {
        Order order = pending();
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            return new RazorpayService.LinkState("partially_paid", new BigDecimal("100"), "pay_partial");
        });
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_REVIEW_REQUIRED);
        verify(alerts).raise(eq("PAYMENT_PARTIAL"), anyString(), any(), any());
        verify(gateway, never()).cancelPaymentLink(anyString());
    }

    @Test
    void providerFailureStillCancelsButFailedRevokeRetainsBinding() throws Exception {
        Order order = pending();
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            throw new IllegalStateException("mock provider unavailable");
        });
        doAnswer(invocation -> {
            outsideTransaction();
            throw new IllegalStateException("mock cancellation unavailable");
        }).when(gateway).cancelPaymentLink("plink_saved");
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payment(order).getGatewayLinkId()).isEqualTo("plink_saved");
        verify(notifier).orderCancelled(any(), any());
    }

    @Test
    void successfulRevokeDoesNotClearAReplacementBinding() throws Exception {
        Order order = pending();
        doAnswer(invocation -> {
            outsideTransaction();
            Payment changed = payment(order);
            changed.setGatewayLinkId("plink_replacement");
            payments.save(changed);
            return null;
        }).when(gateway).cancelPaymentLink("plink_saved");
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payment(order).getGatewayLinkId()).isEqualTo("plink_replacement");
    }

    @Test
    void deliveryDateMovedBeyondCutoffDuringFetchIsRechecked() throws Exception {
        Order order = pending();
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            admin.updateOrderDeliveryDate(Objects.requireNonNull(order.getId()), LocalDate.now().plusDays(5));
            return new RazorpayService.LinkState("created", BigDecimal.ZERO, null);
        });
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        verify(gateway, never()).cancelPaymentLink(anyString());
    }

    @Test
    void draftCancellationNotifiesOutsideTransactionWithoutFetchingProvider() throws Exception {
        Order order = pending();
        order.setStatus(OrderStatus.DRAFT);
        orderRepository.save(order);
        doAnswer(invocation -> {
            outsideTransaction();
            assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            return null;
        }).when(whatsAppClient).sendText(eq(customer.getPhone()), anyString());
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(gateway, never()).fetchPaymentLink(anyString());
        verify(whatsAppClient).sendText(eq(customer.getPhone()), anyString());
    }

    @Test
    void concurrentConfirmationDuringFetchIsNotCancelled() throws Exception {
        Order order = pending();
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()), "pay_webhook",
                    "plink_saved", new BigDecimal("400"));
            return new RazorpayService.LinkState("created", BigDecimal.ZERO, null);
        });
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(gateway, never()).cancelPaymentLink(anyString());
        verify(notifier, never()).orderCancelled(any(), any());
    }

    @Test
    void expiredWebAttemptWithPaidLinkStillRequiresReview() throws Exception {
        Order order = pending();
        order.setChannel(OrderChannel.WEB);
        orderRepository.save(order);
        jdbcTemplate.update("""
                insert into web_checkout_attempts
                (id,customer_id,order_id,idempotency_key,request_hash,cart_version,quote_token_hash,quote_hash,
                 snapshot,state,provider_reference,provider_link_id,expires_at,created_at,updated_at)
                values ('cutoff_attempt',?,?,'cutoff_test_key_12345',?,1,?,?,'{}'::jsonb,'PAYABLE',
                        'cutoff_reference','plink_saved',now()-interval '1 minute',now(),now())
                """, customer.getId(), order.getId(), "a".repeat(64), "b".repeat(64), "c".repeat(64));
        when(gateway.fetchPaymentLink("plink_saved")).thenAnswer(invocation -> {
            outsideTransaction();
            return new RazorpayService.LinkState("paid", new BigDecimal("400"), "pay_saved");
        });
        cutoff.cancelUnfinishedOrders();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_REVIEW_REQUIRED);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.REVIEW_REQUIRED);
        verify(alerts).raise(eq("PAYMENT_WEB_BINDING"), anyString(), any(), any());
        verify(notifier, never()).orderConfirmed(any());
        verify(gateway, never()).cancelPaymentLink(anyString());
    }

    @Test
    void jobSuspendsAnAmbientTransactionAndRestoresIt() {
        Order order = pending(); // committed fixtures must be visible to the independent coordinator
        new TransactionTemplate(Objects.requireNonNull(transactionManager)).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            cutoff.cancelUnfinishedOrders();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private Order pending() {
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setTotalAmount(new BigDecimal("400"));
        order.setDeliveryDate(LocalDate.now().plusDays(1));
        order = orderRepository.save(order);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setProvider("RAZORPAY");
        payment.setAmount(new BigDecimal("400"));
        payment.setGatewayLinkId("plink_saved");
        payments.save(payment);
        return order;
    }

    private Order pendingWeb() {
        Order order = pending();
        order.setChannel(OrderChannel.WEB);
        order = orderRepository.save(order);
        jdbcTemplate.update("""
                insert into web_checkout_attempts
                (id,customer_id,order_id,idempotency_key,request_hash,cart_version,quote_token_hash,quote_hash,
                 snapshot,state,provider_reference,provider_link_id,expires_at,created_at,updated_at)
                values ('notification_attempt',?,?,'notification_key_12345',?,1,?,?,'{}'::jsonb,'PAYABLE',
                        'notification_reference','plink_saved',now()+interval '10 minutes',now(),now())
                """, customer.getId(), order.getId(), "a".repeat(64), "b".repeat(64), "c".repeat(64));
        return order;
    }

    private void observeCommittedNotifications(Order order) {
        doAnswer(invocation -> { assertCommittedOutsideTransaction(order); return null; })
                .when(notifier).orderConfirmed(any());
        doAnswer(invocation -> { assertCommittedOutsideTransaction(order); return null; })
                .when(alerts).raise(eq("ORDER_PAID"), anyString(), any(), any());
    }

    private void assertCommittedOutsideTransaction(Order order) throws SQLException {
        outsideTransaction();
        // An independent connection proves both visibility and actual advisory-lock release;
        // Spring's afterCommit callback alone still has transaction resources bound.
        try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                        select o.status, p.status from orders o join payments p on p.order_id=o.id where o.id=?
                        """)) {
                    statement.setLong(1, Objects.requireNonNull(order.getId()));
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("CONFIRMED");
                        assertThat(result.getString(2)).isEqualTo("GATEWAY_CAPTURED");
                    }
                }
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("select pg_try_advisory_xact_lock(23734322354538565)")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).as("capacity lock released before network effects").isTrue();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private void failSettlementAtCommit() {
        // Stub the Mockito target, not the Spring proxy: configuring acquire() must not
        // itself pass through MANDATORY advice before a settlement transaction exists.
        CapacityLock target = AopTestUtils.getUltimateTargetObject(Objects.requireNonNull(capacityLock));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("mock settlement commit failure");
                }
            });
            return null;
        }).when(target).acquire();
    }

    private void observeReceiptPersistenceContext(Order order) {
        var menuItem = itemRepository.findAll().get(0);
        OrderItem line = new OrderItem();
        line.setOrder(order);
        line.setMenuItem(menuItem);
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("400"));
        line.setSubtotal(new BigDecimal("400"));
        orderItems.save(line);
        doAnswer(invocation -> {
            assertCommittedOutsideTransaction(order);
            // Match receipt rendering: a derived query followed by lazy menu/category reads.
            var receiptLine = orderItems.findAllByOrderId(order.getId()).get(0);
            assertThat(receiptLine.getMenuItem().getCategory().getName()).isNotBlank();
            // A transactional repository method must suspend/resume the receipt context safely.
            assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(orderItems.findAllByOrderId(order.getId()).get(0)
                    .getMenuItem().getCategory().getName()).isNotBlank();
            outsideTransaction();
            return null;
        }).when(notifier).orderConfirmed(any());
        doAnswer(invocation -> {
            assertCommittedOutsideTransaction(order);
            // Exercise the same synchronization suspension as the real REQUIRES_NEW alert.
            TransactionTemplate alertTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
            alertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            alertTransaction.executeWithoutResult(status ->
                    assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED));
            outsideTransaction();
            return null;
        }).when(alerts).raise(eq("ORDER_PAID"), anyString(), any(), any());
    }

    private void assertReceiptEffectsCleanedUp() {
        verify(notifier).orderConfirmed(any());
        verify(alerts).raise(eq("ORDER_PAID"), anyString(), any(), any());
        outsideTransaction();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(Objects.requireNonNull(entityManagerFactory))).isFalse();
    }

    private void assertRolledBackWithoutNotifications(Order order) {
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment(order).getGatewayPaymentId()).isNull();
        verify(notifier, never()).orderConfirmed(any());
        verify(alerts, never()).raise(anyString(), anyString(), any(), any());
    }

    private Order reload(Order order) {
        return orderRepository.findById(Objects.requireNonNull(order.getId())).orElseThrow();
    }

    private Payment payment(Order order) {
        return payments.findByOrder(order).orElseThrow();
    }

    private static void outsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
}