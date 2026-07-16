package com.tranche.bakery;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.eq;
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

    // ── 1. Happy path: single order from hi → payment screenshot ─────────────

    @Test
    void happyPath_singleOrder_endToEnd() {
        Long orderId = driveToPaymentQr();

        sendImage("media-test-001");

        assertState("IDLE");
        assertOrderStatus(orderId, OrderStatus.CONFIRMED);
    }

    @Test
    void bunsOnlyOrder_skipsLoafPreference() {
        var bunsCategory = categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc().get(1);
        String itemId = firstItemId(bunsCategory.getId().toString());

        send("hi");
        send("order");
        send(bunsCategory.getId().toString());
        send(itemId);
        send("1");
        send("view_order");
        send(nextDeliveryDate());
        send("use_address");
        send("pref_gate");

        assertState("ORDER_CONFIRM");
        assertThat(sentButtonBodies).noneMatch(text -> text.contains("How would you like the loaves"));
    }

    // ── Bagel needs 48h lead: too-early dates are rejected ───────────────

    @Test
    void bagelOrder_rejectsTooEarlyDate() {
        String catId  = categoryIdByName("Breakfast & Specialty");
        String itemId = itemIdByNameContains(catId, "bagel");

        send("hi");
        send("order");
        send(catId);
        send(itemId);
        send("1");
        send("view_order");
        assertState("ORDER_SELECT_DATE");

        // Today is always before the bagel earliest date (>= today + 2)
        send(LocalDate.now().toString());
        assertState("ORDER_SELECT_DATE");
        assertThat(sentTexts).anyMatch(t -> t.contains("48-hour"));

        // A valid bagel date advances past date selection
        send(bagelEarliestDate());
        assertState("ADDRESS_CONFIRM");
    }

    // ── Focaccia is weekend-only: weekday dates are rejected ──────────────

    @Test
    void focacciaOrder_rejectsWeekday() {
        String catId  = categoryIdByName("Breakfast & Specialty");
        String itemId = itemIdByNameContains(catId, "focaccia");

        send("hi");
        send("order");
        send(catId);
        send(itemId);
        send("1");
        send("view_order");
        assertState("ORDER_SELECT_DATE");

        // A future Wednesday clears the lead time but is not a weekend -> rejected
        send(nextWeekday(DayOfWeek.WEDNESDAY).toString());
        assertState("ORDER_SELECT_DATE");
        assertThat(sentTexts).anyMatch(t -> t.contains("weekend"));

        // A weekend date is accepted
        send(nextWeekend(DayOfWeek.SATURDAY).toString());
        assertState("ADDRESS_CONFIRM");
    }

    // -- Daily capacity: a fully-booked date is hidden, rejected, and explained ----

    @Test
    void fullyBookedDate_isHiddenRejectedAndExplained() {
        // Fill the soonest normal delivery day to the test capacity (3 items)
        LocalDate full = LocalDate.parse(nextDeliveryDate());
        fillDateCapacity(full, 3);

        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        send("hi");
        send("order");
        send(catId);
        send(itemId);
        send("1");
        send("view_order");
        assertState("ORDER_SELECT_DATE");

        // Entry note explains why the soonest day is missing
        assertThat(sentTexts).anyMatch(t -> t.contains("fully booked"));

        // Selecting the full date is rejected and re-prompts
        send(full.toString());
        assertState("ORDER_SELECT_DATE");
        assertThat(sentTexts).anyMatch(t -> t.contains("fully booked"));

        // The next available (non-Monday) date is accepted
        LocalDate next = full.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.MONDAY) next = next.plusDays(1);
        send(next.toString());
        assertState("ADDRESS_CONFIRM");
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

    // ── 2. Customer cancels via the cancel_<id> button on the QR message ─────

    @Test
    void cancelOrder_viaCancelIdButton() {
        Long orderId = driveToPaymentQr();

        // Simulates the customer tapping the "Cancel Order" button whose id is cancel_<N>
        send("cancel_" + orderId);

        assertState("IDLE");
        assertOrderStatus(orderId, OrderStatus.CANCELLED);
        assertThat(sentTexts).anyMatch(t -> t.contains("cancelled"));
    }

    // ── 3. Second order for a different delivery date → separate-order warning ─

    @Test
    void multiOrder_differentDate_showsSeparateOrderWarning() {
        // Confirm order 1 on D1
        driveToPaymentQr(nextDeliveryDate());

        // Reset conversation without cancelling the confirmed order
        send("hi");

        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        String date2  = secondDeliveryDate();    // D2 ≠ D1

        send("order");
        send(catId);
        send(itemId);
        send("1");
        send("view_order");
        send(date2);    // SaveDeliveryDateAction: 1 pending, different date → ORDER_CONFIRM_SEPARATE

        assertState("ORDER_CONFIRM_SEPARATE");
        // Customer should see a message warning them this will be a separate order
        assertThat(sentButtonBodies).anyMatch(b -> b.toLowerCase().contains("separate"));
    }

    // ── 4. Customer orders for the same date twice → items are merged ─────────

    @Test
    void multiOrder_sameDate_mergesIntoExistingOrder() {
        String date   = nextDeliveryDate();
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        // Order 1: 1 × item, date D
        send("hi");
        send("order");
        send(catId); send(itemId); send("1"); send("view_order");
        send(date);
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");    // order 1 → PENDING_CONFIRMATION

        List<Order> after1 = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(after1).hasSize(1);
        Long order1Id = after1.get(0).getId();

        // Reset conversation; order 1 stays PENDING_CONFIRMATION
        send("hi");

        // Order 2: 2 × same item, same date D → SaveDeliveryDateAction Case 1: merge
        send("order");
        send(catId); send(itemId); send("2"); send("view_order");
        send(date);     // merge triggers, draft cancelled, redirected to ORDER_CONFIRM

        assertState("ORDER_CONFIRM");

        // Draft was cancelled — no DRAFT orders remain
        List<Order> drafts = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.DRAFT);
        assertThat(drafts).as("draft order should have been cancelled after merge").isEmpty();

        // Order 1 is still the single PENDING_CONFIRMATION order
        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getId()).isEqualTo(order1Id);
    }

    // ── 5. Two confirmed orders, screenshot prompts order selection ───────────

    @Test
    void multiOrder_screenshotWithTwoPendingOrders_asksWhichOrder() {
        // Order 1 on D1
        Long order1Id = driveToPaymentQr(nextDeliveryDate());
        // Conversation is now in PAYMENT_PENDING; reset so we can place order 2
        send("hi");

        // Order 2 on D2 — goes through SEPARATE warning then confirm
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        String date2  = secondDeliveryDate();

        send("order");
        send(catId); send(itemId); send("1"); send("view_order");
        send(date2);                // ORDER_CONFIRM_SEPARATE
        send("continue_order");     // ADDRESS_GATE → ADDRESS_CONFIRM
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

        // Two pending orders → must ask which order this payment is for
        assertState("PAYMENT_ORDER_SELECT");

        // Customer picks order 1
        send("pay_" + order1Id);

        assertState("IDLE");
        assertOrderStatus(order1Id, OrderStatus.CONFIRMED);
        assertOrderStatus(order2Id, OrderStatus.PENDING_CONFIRMATION);   // untouched
    }

    // ── 6. Separate-order warning → customer cancels the new draft ────────────

    @Test
    void multiOrder_differentDate_cancelFromWarning() {
        driveToPaymentQr(nextDeliveryDate());
        send("hi");

        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        send("order");
        send(catId); send(itemId); send("1"); send("view_order");
        send(secondDeliveryDate());     // ORDER_CONFIRM_SEPARATE

        assertState("ORDER_CONFIRM_SEPARATE");

        send("cancel_order");           // CANCEL_ORDER action → MAIN_MENU

        assertState("MAIN_MENU");
        List<Order> drafts = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.DRAFT);
        assertThat(drafts).as("draft should be cancelled when customer declines separate order").isEmpty();
    }

    // ── 7. Admin cancel notifies customer via WhatsApp ──────────────────────────

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

    // ── 8. Same item added twice consolidates into a single line ────────────────

    @Test
    void addSameItemTwice_consolidatesQuantity() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        send("hi");
        send("order");
        send(catId); send(itemId); send("2");   // 2 × item
        send("add_item");                        // add another
        send(catId); send(itemId); send("1");   // 1 × same item

        List<Order> drafts = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.DRAFT);
        assertThat(drafts).hasSize(1);

        List<OrderItem> items = orderItemRepository.findAllByOrderId(drafts.get(0).getId());
        assertThat(items).as("same item should be one consolidated line").hasSize(1);
        assertThat(items.get(0).getQuantity()).as("quantity should be 2 + 1 = 3").isEqualTo(3);
    }

    // ── 9. Max 3 pending orders — 4th order is blocked ──────────────────────────

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
        send(catId); send(itemId); send("1"); send("view_order");
        send(date2);
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
        send(catId); send(itemId); send("1"); send("view_order");
        send(date3);
        send("continue_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");

        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).as("should have 3 pending orders").hasSize(3);

        // 4th order attempt — should be blocked
        LocalDate d4 = d3.plusDays(1);
        if (d4.getDayOfWeek() == DayOfWeek.MONDAY) d4 = d4.plusDays(1);
        String date4 = d4.toString();

        sentTexts.clear();
        send("hi");
        send("order");
        send(catId); send(itemId); send("1"); send("view_order");
        send(date4);

        // Should be redirected to IDLE with a blocking message
        assertState("IDLE");
        assertThat(sentTexts).anyMatch(t -> t.contains("3 orders awaiting payment"));

        // Still exactly 3 pending orders — 4th was cancelled
        List<Order> pendingAfter = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pendingAfter).as("should still have 3 pending orders").hasSize(3);
    }
}
