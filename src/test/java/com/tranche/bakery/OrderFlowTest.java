package com.tranche.bakery;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderStatus;

/**
 * End-to-end scenario tests for the WhatsApp ordering flow.
 *
 * Each test drives FlowEngine via send()/sendImage() helpers,
 * backed by a real Postgres TestContainer and a mocked WhatsAppClient.
 * No actual WhatsApp API calls are made.
 */
class OrderFlowTest extends FlowScenarioBase {

    @Autowired private AdminService adminService;
    @Autowired private OrderItemRepository orderItemRepository;

    // â”€â”€ 1. Happy path: single order from hi â†’ payment screenshot â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void happyPath_singleOrder_endToEnd() {
        Long orderId = driveToPaymentQr();

        sendImage("media-test-001");

        assertState("IDLE");
        assertOrderStatus(orderId, OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
    }

    @Test
    void bunsOnlyOrder_skipsLoafPreference() {
        var bunsCategory = categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc().get(1);
        String itemId = firstItemId(bunsCategory.getId().toString());

        send("hi");
        send("order");
        send(nextDeliveryDate());
        send(bunsCategory.getId().toString());
        send(itemId);
        send("1");
        send("view_order");
        send("use_address");
        send("pref_gate");

        assertState("ORDER_CONFIRM");
        assertThat(sentButtonBodies).noneMatch(text -> text.contains("How would you like the loaves"));
    }

    // â”€â”€ Bagel needs 48h lead: too-early dates are rejected â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void bagelItem_menuFiltersByLeadTime() {
        String catId = categoryIdByName("Breakfast & Specialty");

        // A bagel-eligible date offers the bagel.
        send("hi");
        send("order");
        send(bagelEarliestDate());
        assertState("ORDER_SELECT_CATEGORY");
        send(catId);
        assertThat(sentListRows).as("bagel offered once the 48h lead is met")
                .anyMatch(t -> t.toLowerCase().contains("bagel"));

        // A valid delivery day still inside the 48h window hides the bagel. When the
        // only near day is a closed Monday, no such window exists, so this is skipped.
        LocalDate bagelOk = LocalDate.parse(bagelEarliestDate());
        LocalDate probe = LocalDate.now().plusDays(LocalTime.now().getHour() >= 23 ? 2 : 1);
        while (probe.getDayOfWeek() == DayOfWeek.MONDAY) probe = probe.plusDays(1);
        if (probe.isBefore(bagelOk)) {
            send("hi");
            send("order");
            send(probe.toString());
            assertState("ORDER_SELECT_CATEGORY");
            send(catId);
            assertThat(sentListRows).as("bagel hidden when the date is within 48h")
                    .noneMatch(t -> t.toLowerCase().contains("bagel"));
        }
    }

    // â”€â”€ Focaccia is weekend-only: weekday dates are rejected â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void focacciaItem_hiddenOnWeekday() {
        String catId = categoryIdByName("Breakfast & Specialty");

        send("hi");
        send("order");

        // A weekday date: focaccia is weekend-only, so it isn't offered.
        send(nextWeekday(DayOfWeek.WEDNESDAY).toString());
        assertState("ORDER_SELECT_CATEGORY");
        send(catId);
        assertThat(sentListRows).as("focaccia hidden on a weekday")
                .noneMatch(t -> t.toLowerCase().contains("focaccia"));

        // A weekend date offers focaccia.
        send("hi");
        send("order");
        send(nextWeekend(DayOfWeek.SATURDAY).toString());
        assertState("ORDER_SELECT_CATEGORY");
        send(catId);
        assertThat(sentListRows).as("focaccia offered on a weekend")
                .anyMatch(t -> t.toLowerCase().contains("focaccia"));
    }

    // -- Daily capacity: a fully-booked date is hidden, rejected, and explained ----

    @Test
    void fullyBookedDate_isHiddenRejectedAndExplained() {
        // Fill the soonest normal delivery day to the test capacity (3 items)
        LocalDate full = LocalDate.parse(nextDeliveryDate());
        fillDateCapacity(full, 3);

        send("hi");
        send("order");
        assertState("ORDER_SELECT_DATE");

        // Entry note explains why the soonest day is missing
        assertThat(sentTexts).anyMatch(t -> t.contains("fully booked"));

        // Selecting the full date is rejected and re-prompts
        send(full.toString());
        assertState("ORDER_SELECT_DATE");
        assertThat(sentTexts).anyMatch(t -> t.contains("fully booked"));

        // The next available (non-Monday) date is accepted -> browse the menu
        LocalDate next = full.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.MONDAY) next = next.plusDays(1);
        send(next.toString());
        assertState("ORDER_SELECT_CATEGORY");
    }

    // Multiple full days: the notification mentions ALL of them, not just the first.
    @Test
    void multipleFullDays_allMentionedInNotification() {
        // Fill two consecutive deliverable days
        LocalDate first = LocalDate.parse(nextDeliveryDate());
        LocalDate second = first.plusDays(1);
        while (second.getDayOfWeek() == DayOfWeek.MONDAY) second = second.plusDays(1);
        fillDateCapacity(first, 3);
        fillDateCapacity(second, 3);

        send("hi");
        send("order");
        assertState("ORDER_SELECT_DATE");

        // The notification should mention both full days
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM");
        String firstFormatted = first.format(fmt);
        String secondFormatted = second.format(fmt);

        assertThat(sentTexts).anyMatch(t ->
                t.contains(firstFormatted) && t.contains(secondFormatted)
                        && t.contains("fully booked"));
    }

    // Non-consecutive full days (e.g. Tuesday + Sunday) are both reported even when
    // there are available days in between.
    @Test
    void nonConsecutiveFullDays_bothMentionedInNotification() {
        // Find two non-consecutive deliverable days with available days between them
        LocalDate first = LocalDate.parse(nextDeliveryDate());
        // Skip one available day, then find the next deliverable day after that
        LocalDate middle = first.plusDays(1);
        while (middle.getDayOfWeek() == DayOfWeek.MONDAY) middle = middle.plusDays(1);
        LocalDate later = middle.plusDays(1);
        while (later.getDayOfWeek() == DayOfWeek.MONDAY) later = later.plusDays(1);

        // Fill first and later, leave middle open
        fillDateCapacity(first, 3);
        fillDateCapacity(later, 3);

        send("hi");
        send("order");
        assertState("ORDER_SELECT_DATE");

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM");
        String firstFormatted = first.format(fmt);
        String laterFormatted = later.format(fmt);

        // Both full days should be mentioned in the notification
        assertThat(sentTexts).anyMatch(t ->
                t.contains(firstFormatted) && t.contains(laterFormatted)
                        && t.contains("fully booked"));
    }

    // Wider gap (e.g. Tuesday + the following Sunday, with several open days between):
    // every full day inside the picker window must still be named, not just the soonest.
    @Test
    void widelySeparatedFullDays_bothMentionedInNotification() {
        LocalDate first = LocalDate.parse(nextDeliveryDate());
        LocalDate later = first.plusDays(5);
        while (later.getDayOfWeek() == DayOfWeek.MONDAY) later = later.plusDays(1);

        fillDateCapacity(first, 3);
        fillDateCapacity(later, 3);

        send("hi");
        send("order");
        assertState("ORDER_SELECT_DATE");

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM");
        String firstFormatted = first.format(fmt);
        String laterFormatted = later.format(fmt);

        assertThat(sentTexts).anyMatch(t ->
                t.contains(firstFormatted) && t.contains(laterFormatted)
                        && t.contains("fully booked"));
    }

    // Seed a CONFIRMED order for another customer that books `qty` items on `date`.
    private void fillDateCapacity(LocalDate date, int qty) {
        var item = itemRepository.findAll().get(0);
        Long custId = jdbcTemplate.queryForObject(
                "INSERT INTO customers (phone) VALUES (?) RETURNING id",
                Long.class, "9198" + (System.nanoTime() % 100000000L));
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO orders (customer_id, status, fulfillment_type, delivery_charge, delivery_date) "
                        + "VALUES (?, 'CONFIRMED', 'delivery', 0, ?) RETURNING id",
                Long.class, custId, java.sql.Date.valueOf(date));
        jdbcTemplate.update(
                "INSERT INTO order_items (order_id, menu_item_id, quantity, unit_price, subtotal) "
                        + "VALUES (?, ?, ?, 0, 0)",
                orderId, item.getId(), qty);
    }

    private String categoryIdByName(String name) {
        return categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc().stream()
                .filter(cat -> cat.getName().equals(name))
                .findFirst().orElseThrow()
                .getId().toString();
    }

    private String itemIdByNameContains(String categoryId, String needle) {
        var category = categoryRepository.findById(Long.parseLong(categoryId)).orElseThrow();
        return itemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category).stream()
                .filter(i -> i.getName().toLowerCase().contains(needle))
                .findFirst().orElseThrow()
                .getId().toString();
    }

    // Earliest valid date for a bagel cart: base lead + 1 extra day, skipping Monday.
    private String bagelEarliestDate() {
        LocalDate d = LocalDate.now().plusDays(LocalTime.now().getHour() >= 23 ? 3 : 2);
        while (d.getDayOfWeek() == DayOfWeek.MONDAY) d = d.plusDays(1);
        return d.toString();
    }

    // Next occurrence of the given weekday at least a week out (safely past lead time).
    private LocalDate nextWeekday(DayOfWeek dow) {
        LocalDate d = LocalDate.now().plusDays(7);
        while (d.getDayOfWeek() != dow) d = d.plusDays(1);
        return d;
    }

    // Next occurrence of the given weekend day that respects the base lead time.
    private LocalDate nextWeekend(DayOfWeek dow) {
        LocalDate d = LocalDate.now().plusDays(LocalTime.now().getHour() >= 23 ? 2 : 1);
        while (d.getDayOfWeek() != dow) d = d.plusDays(1);
        return d;
    }

    // â”€â”€ 2. Customer cancels via the cancel_<id> button on the QR message â”€â”€â”€â”€â”€

    @Test
    void cancelOrder_viaCancelIdButton() {
        Long orderId = driveToPaymentQr();

        // Simulates the customer tapping the "Cancel Order" button whose id is cancel_<N>
        send("cancel_" + orderId);

        assertState("IDLE");
        assertOrderStatus(orderId, OrderStatus.CANCELLED);
        assertThat(sentTexts).anyMatch(t -> t.contains("cancelled"));
    }

    // â”€â”€ 3. Second order for a different delivery date â†’ separate-order warning â”€

    @Test
    void multiOrder_differentDate_showsSeparateOrderWarning() {
        // Confirm order 1 on D1
        driveToPaymentQr(nextDeliveryDate());

        // Reset conversation without cancelling the confirmed order
        send("hi");

        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        String date2  = secondDeliveryDate();    // D2 â‰  D1

        send("order");
        send(date2);
        send(catId);
        send(itemId);
        send("1");
        send("view_order");    // SaveDeliveryDateAction: 1 pending, different date â†’ ORDER_CONFIRM_SEPARATE

        assertState("ORDER_CONFIRM_SEPARATE");
        // Customer should see a message warning them this will be a separate order
        assertThat(sentButtonBodies).anyMatch(b -> b.toLowerCase().contains("separate"));
    }

    // â”€â”€ 4. Customer orders for the same date twice â†’ items are merged â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void multiOrder_sameDate_mergesIntoExistingOrder() {
        String date   = nextDeliveryDate();
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        // Order 1: 1 Ã— item, date D
        send("hi");
        send("order");
        send(date);
        send(catId); send(itemId); send("1"); send("view_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");    // order 1 â†’ PENDING_CONFIRMATION

        List<Order> after1 = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(after1).hasSize(1);
        Long order1Id = after1.get(0).getId();

        // Reset conversation; order 1 stays PENDING_CONFIRMATION
        send("hi");

        // Order 2: 2 Ã— same item, same date D â†’ SaveDeliveryDateAction Case 1: merge
        send("order");
        send(date);
        send(catId); send(itemId); send("2"); send("view_order");     // merge triggers, draft cancelled, redirected to ORDER_CONFIRM

        assertState("ORDER_CONFIRM");

        // Draft was cancelled â€” no DRAFT orders remain
        List<Order> drafts = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.DRAFT);
        assertThat(drafts).as("draft order should have been cancelled after merge").isEmpty();

        // Order 1 is still the single PENDING_CONFIRMATION order
        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getId()).isEqualTo(order1Id);
    }

    // â”€â”€ 5. Two confirmed orders, screenshot prompts order selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void multiOrder_screenshotWithTwoPendingOrders_asksWhichOrder() {
        // Order 1 on D1
        Long order1Id = driveToPaymentQr(nextDeliveryDate());
        // Conversation is now in PAYMENT_PENDING; reset so we can place order 2
        send("hi");

        // Order 2 on D2 â€” goes through SEPARATE warning then confirm
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        String date2  = secondDeliveryDate();

        send("order");
        send(date2);
        send(catId); send(itemId); send("1"); send("view_order");
        send("continue_order");     // ADDRESS_GATE â†’ ADDRESS_CONFIRM
        send("use_address");        // DELIVERY_PREFERENCE
        send("pref_gate");          // LOAF_PREFERENCE
        send("loaf_sliced");        // ORDER_CONFIRM
        send("confirm");            // PAYMENT_PENDING

        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        Long order2Id = pending.stream()
                .filter(o -> !o.getId().equals(order1Id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a second PENDING_CONFIRMATION order"))
                .getId();

        // Conversation is in PAYMENT_PENDING; customer sends screenshot
        sendImage("media-test-002");

        // Two pending orders â†’ must ask which order this payment is for
        assertState("PAYMENT_ORDER_SELECT");

        // Customer picks order 1
        send("pay_" + order1Id);

        assertState("IDLE");
        assertOrderStatus(order1Id, OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
        assertOrderStatus(order2Id, OrderStatus.PENDING_CONFIRMATION);   // untouched
    }

    // -- Order status with two pending orders renders and offers per-order Pay buttons --
    // Regression: button titles must stay within WhatsApp's 20-char cap or the Cloud API
    // rejects the whole interactive message and the status silently never reaches the
    // customer. Titles are short ("Pay + date") and each re-sends that order's QR.
    @Test
    void orderStatus_twoPendingOrders_offersShortPayButtons() {
        Long order1Id = driveToPaymentQr(nextDeliveryDate());
        send("hi");

        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        send("order");
        send(secondDeliveryDate());
        send(catId); send(itemId); send("1"); send("view_order");
        send("continue_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");

        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).as("two pending orders").hasSize(2);
        Long order2Id = pending.stream().filter(o -> !o.getId().equals(order1Id))
                .findFirst().orElseThrow().getId();

        // Both orders carry a real TRB- reference (set at confirm), so the old code would
        // have produced 24-char button titles.
        assertThat(pending).allSatisfy(o ->
                assertThat(o.getOrderNumber()).as("order number assigned at confirm").isNotNull());

        // Return to the main menu, then open Info -> My Order Status.
        send("hi");
        assertState("MAIN_MENU");
        send("info");
        assertState("INFO_MENU");
        sentButtonBodies.clear();
        sentButtonTitles.clear();
        send("order_status");

        // The status list actually reached the customer...
        assertThat(sentButtonBodies)
                .as("order status list is rendered")
                .anyMatch(b -> b.toLowerCase().contains("active orders"));

        // ...and every button title respects WhatsApp's 20-char cap.
        assertThat(sentButtonTitles)
                .as("no button title exceeds WhatsApp's 20-char limit")
                .allMatch(t -> t.length() <= 20);

        // One Pay button is offered per unpaid order.
        assertThat(sentButtonTitles.stream().filter(t -> t.startsWith("Pay")).toList())
                .as("a Pay button per unpaid order")
                .hasSize(2);

        // Selective cancel still works via the global cancel_<id> payload.
        send("cancel_" + order2Id);
        assertOrderStatus(order2Id, OrderStatus.CANCELLED);
        assertOrderStatus(order1Id, OrderStatus.PENDING_CONFIRMATION);
    }

    // -- Re-pay: a customer who dismissed the QR can pay again from order status --
    // The QR is only sent once at checkout; if the customer scrolls away, order status
    // now lets them re-surface it (paynow_<id>) and complete payment without a new order.
    @Test
    void orderStatus_singlePendingOrder_payNowReSendsQrAndConfirms() {
        Long orderId = driveToPaymentQr();

        // Leave the payment screen and open Info -> My Order Status.
        send("hi");
        send("info");
        send("order_status");

        // A single unpaid order offers Pay Now (alongside Cancel Order).
        assertThat(sentButtonTitles).as("Pay Now offered on single-order status").contains("Pay Now");

        // Tapping Pay Now re-surfaces the QR and returns to PAYMENT_PENDING.
        send("paynow_" + orderId);
        assertState("PAYMENT_PENDING");

        // From there the customer pays and the order confirms as usual.
        sendImage("media-repay-001");
        assertState("IDLE");
        assertOrderStatus(orderId, OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
    }

    // -- Re-pay is selective: with multiple unpaid orders, the tapped order's QR is sent --
    @Test
    void orderStatus_multiplePending_payNowTargetsChosenOrder() {
        Long order1Id = driveToPaymentQr(nextDeliveryDate());
        send("hi");

        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        send("order");
        send(secondDeliveryDate());
        send(catId); send(itemId); send("1"); send("view_order");
        send("continue_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");

        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).hasSize(2);
        Long order2Id = pending.stream().filter(o -> !o.getId().equals(order1Id))
                .findFirst().orElseThrow().getId();

        // Re-pay order 2 specifically from the status screen: its QR is re-sent and the
        // conversation re-enters payment scoped to that order (order 1 is untouched).
        send("paynow_" + order2Id);
        assertState("PAYMENT_PENDING");
        assertThat(conversation.getContext().get("orderId"))
                .as("re-pay targets the chosen order")
                .isEqualTo(order2Id.toString());
        assertOrderStatus(order2Id, OrderStatus.PENDING_CONFIRMATION);
        assertOrderStatus(order1Id, OrderStatus.PENDING_CONFIRMATION);
    }

    // -- Order status with actionable buttons rests and waits instead of burying them --
    // Regression: ORDER_STATUS auto-transitions to MAIN_MENU, which used to dump the home
    // menu right under the Pay Now / Cancel buttons. When actionable buttons are shown the
    // conversation now rests in IDLE so the customer can tap without the menu piling on top.
    @Test
    void orderStatus_singlePendingOrder_waitsForButtonsAndDoesNotDumpMenu() {
        driveToPaymentQr();

        send("hi");
        send("info");
        assertState("INFO_MENU");
        sentTexts.clear();
        sentButtonBodies.clear();
        sentButtonTitles.clear();
        send("order_status");

        // The Pay Now / Cancel buttons reach the customer...
        assertThat(sentButtonTitles).contains("Pay Now", "Cancel Order");

        // ...the conversation rests waiting for the tap (no auto-jump to MAIN_MENU)...
        assertState("IDLE");

        // ...and the home menu is NOT dumped underneath the buttons.
        assertThat(sentButtonBodies)
                .as("home menu must not be shown under the order-status buttons")
                .noneMatch(b -> b.contains("How can we help you today"));
    }

    // -- Per-date item cap block message includes the how-to-pay hint --
    // When a same-date unpaid order is already at the item cap, picking that date again is
    // blocked. The block message must tell the customer how to pay (hi -> Info -> My Order
    // Status), matching the wording on the other "full" screens.
    @Test
    void perDateItemCap_blockMessageIncludesPayHint() {
        String date   = nextDeliveryDate();
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        // Order 1: fill the cart for date D to the 3-item cap, confirm to PENDING.
        send("hi");
        send("order");
        send(date);
        send(catId); send(itemId); send("3"); send("view_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");

        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).as("one pending order at the item cap").hasSize(1);

        // New order, same date D -> the per-date cap block fires.
        send("hi");
        send("order");
        sentTexts.clear();
        send(date);

        assertState("ORDER_SELECT_DATE");
        assertThat(sentTexts)
                .as("cap block explains the cart is full")
                .anyMatch(t -> t.contains("already full"));
        assertThat(sentTexts)
                .as("cap block tells the customer how to pay")
                .anyMatch(t -> t.contains("tap *Info*") && t.contains("My Order Status"));
    }

    // â”€â”€ 6. Separate-order warning â†’ customer cancels the new draft â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void multiOrder_differentDate_cancelFromWarning() {
        driveToPaymentQr(nextDeliveryDate());
        send("hi");

        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        send("order");
        send(secondDeliveryDate());
        send(catId); send(itemId); send("1"); send("view_order");     // ORDER_CONFIRM_SEPARATE

        assertState("ORDER_CONFIRM_SEPARATE");

        send("cancel_order");           // CANCEL_ORDER action â†’ MAIN_MENU

        assertState("MAIN_MENU");
        List<Order> drafts = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.DRAFT);
        assertThat(drafts).as("draft should be cancelled when customer declines separate order").isEmpty();
    }

    // â”€â”€ 7. Admin cancel notifies customer via WhatsApp â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void adminCancelOrder_notifiesCustomer() {
        Long orderId = driveToPaymentQr();

        sentTexts.clear();
        adminService.cancelOrder(orderId);

        assertOrderStatus(orderId, OrderStatus.CANCELLED);
        assertThat(sentTexts).anyMatch(t -> t.contains("cancelled"));
        verify(whatsAppClient).sendText(eq(customer.getPhone()),
                org.mockito.ArgumentMatchers.contains("cancelled"));
    }

    // â”€â”€ 8. Same item added twice consolidates into a single line â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void addSameItemTwice_consolidatesQuantity() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        send("hi");
        send("order");
        send(nextDeliveryDate());
        send(catId); send(itemId); send("2");   // 2 Ã— item
        send("add_item");                        // add another
        send(catId); send(itemId); send("1");   // 1 Ã— same item

        List<Order> drafts = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.DRAFT);
        assertThat(drafts).hasSize(1);

        List<OrderItem> items = orderItemRepository.findAllByOrderId(drafts.get(0).getId());
        assertThat(items).as("same item should be one consolidated line").hasSize(1);
        assertThat(items.get(0).getQuantity()).as("quantity should be 2 + 1 = 3").isEqualTo(3);
    }

    // â”€â”€ 9. Max 3 pending orders â€” 4th order is blocked â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void maxPendingOrders_fourthOrderBlocked() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        // Place 3 orders on 3 different dates
        String date1 = nextDeliveryDate();
        driveToPaymentQr(date1);

        String date2 = secondDeliveryDate();
        send("hi");
        send("order");
        send(date2);
        send(catId); send(itemId); send("1"); send("view_order");
        send("continue_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");

        // 3rd date: skip two ahead from date1
        LocalDate d3 = LocalDate.parse(date2).plusDays(1);
        if (d3.getDayOfWeek() == DayOfWeek.MONDAY) d3 = d3.plusDays(1);
        String date3 = d3.toString();

        send("hi");
        send("order");
        send(date3);
        send(catId); send(itemId); send("1"); send("view_order");
        send("continue_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");

        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).as("should have 3 pending orders").hasSize(3);

        // 4th order attempt â€” should be blocked
        LocalDate d4 = d3.plusDays(1);
        if (d4.getDayOfWeek() == DayOfWeek.MONDAY) d4 = d4.plusDays(1);
        String date4 = d4.toString();

        sentTexts.clear();
        send("hi");
        send("order");
        send(date4);

        // Blocked at the date step (before browsing) -> IDLE with a blocking message
        assertState("IDLE");
        assertThat(sentTexts).anyMatch(t -> t.contains("3 orders awaiting payment"));

        // Still exactly 3 pending orders â€” 4th was cancelled
        List<Order> pendingAfter = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pendingAfter).as("should still have 3 pending orders").hasSize(3);
    }

    // -- Date step surfaces the bagel/focaccia heads-up so customers aren't confused --

    @Test
    void dateStep_showsBagelFocacciaHeadsUpNote() {
        send("hi");
        send("order");
        assertState("ORDER_SELECT_DATE");

        assertThat(sentListBodies)
                .as("date prompt explains bagels/focaccia only show on bakeable days")
                .anyMatch(b -> b != null
                        && b.toLowerCase().contains("bagel")
                        && b.toLowerCase().contains("focaccia"));
    }

    // -- Regression: starting a new order clears a previous order's stale context --
    // Date-first runs the date step before any item is added, so a stale orderId/
    // deliveryDate from a completed order must not filter the date list or menu.

    @Test
    void newOrder_clearsStaleContextFromPreviousOrder() {
        String catId = categoryIdByName("Breakfast & Specialty");
        String bagelItemId = itemIdByNameContains(catId, "bagel");
        var bagel = itemRepository.findById(Long.parseLong(bagelItemId)).orElseThrow();

        // A prior COMPLETED order containing a bagel (48h lead) would, if left in
        // context, wrongly hide the soonest normal delivery day for the new order.
        Long staleOrderId = jdbcTemplate.queryForObject(
                "INSERT INTO orders (customer_id, status, fulfillment_type, delivery_charge) "
                        + "VALUES (?, 'COMPLETED', 'DELIVERY', 0) RETURNING id",
                Long.class, customer.getId());
        jdbcTemplate.update(
                "INSERT INTO order_items (order_id, menu_item_id, quantity, unit_price, subtotal) "
                        + "VALUES (?, ?, 1, 0, 0)",
                staleOrderId, bagel.getId());

        // Simulate a returning customer at MAIN_MENU with leftover context (no re-greeting).
        conversation.setState("MAIN_MENU");
        conversation.getContext().put("orderId", staleOrderId.toString());
        conversation.getContext().put("deliveryDate", "2020-01-01");
        conversation.getContext().put("categoryId", catId);
        conversationRepository.save(conversation);
        reloadConversation();

        // Start a new order without greeting, so the "hi" reset does not fire.
        send("order");
        assertState("ORDER_SUGGEST_REORDER");
        assertThat(conversation.getContext())
                .as("stale deliveryDate cleared when a new order starts")
                .doesNotContainKey("deliveryDate");
        assertThat(conversation.getContext())
                .as("stale orderId cleared when a new order starts")
                .doesNotContainKey("orderId");

        // Browse to the date step: the soonest normal day must be offered (it would be
        // hidden if the stale bagel cart were still filtering the list).
        send("browse_menu");
        assertState("ORDER_SELECT_DATE");
        LocalDate soonest = LocalDate.parse(nextDeliveryDate());
        String soonestRow = soonest.format(
                java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM"));
        assertThat(sentListRows)
                .as("soonest normal day offered once stale bagel context is cleared")
                .contains(soonestRow);
    }

    // QR payment description uses the unique order number (TRB-...) not the numeric ID.
    @Test
    void paymentQr_descriptionUsesOrderNumber() {
        Long orderId = driveToPaymentQr();
        Order order = orderRepository.findById(orderId).orElseThrow();

        assertThat(order.getOrderNumber())
                .as("order number should be generated")
                .isNotNull()
                .startsWith("TRB-");

        // Verify sendImage was called with a caption referencing the order number
        ArgumentCaptor<String> captionCaptor = ArgumentCaptor.forClass(String.class);
        verify(whatsAppClient, atLeastOnce()).sendImage(
                eq(customer.getPhone()), org.mockito.ArgumentMatchers.any(), captionCaptor.capture());
        String caption = captionCaptor.getValue();
        assertThat(caption)
                .as("QR caption should reference the unique order number")
                .contains(order.getOrderNumber())
                .doesNotContain("#" + order.getId());
    }

    // Delivery preference is asked only once per order. When the user adds more items
    // to an existing draft that already has a preference, the question is skipped.
    @Test
    void deliveryPreference_askedOnlyOncePerOrder() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        String date   = nextDeliveryDate();

        // Start order, add first item
        send("hi");
        send("order");
        send(date);
        send(catId);
        send(itemId);
        send("1");

        // Go through to delivery preference (first time - should be asked)
        send("view_order");
        send("use_address");    // ADDRESS_CONFIRM â†’ DELIVERY_PREFERENCE
        assertState("DELIVERY_PREFERENCE");
        send("pref_person");    // Sets preference â†’ LOAF_PREFERENCE_GATE

        // Verify the preference was saved on the draft
        Order draft = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.DRAFT).get(0);
        assertThat(draft.getDeliveryPreference()).isEqualTo("IN_PERSON");

        // Now go back from the loaf/confirm step to add more items (via the order
        // confirm â†’ cancel â†’ re-add pattern). Simulate by directly navigating.
        // Use the same draft by re-entering the ORDER_SELECT_CATEGORY state.
        // In reality the user might tap a "change order" or the flow loops back.
        // For this test, manually put conversation back to ORDER_ADD_MORE with same orderId.
        conversation.setState("ORDER_ADD_MORE");
        conversation.getContext().put("orderId", draft.getId().toString());
        conversationRepository.save(conversation);
        reloadConversation();

        // Add another item
        send("add_item");
        send(catId);
        send(itemId);
        send("1");

        // View order again â€” should go through address but SKIP delivery preference
        send("view_order");
        send("use_address");

        // Should NOT be at DELIVERY_PREFERENCE â€” should have skipped to loaf/confirm
        assertThat(conversation.getState())
                .as("delivery preference should be skipped when already set")
                .isNotEqualTo("DELIVERY_PREFERENCE");
    }
}
