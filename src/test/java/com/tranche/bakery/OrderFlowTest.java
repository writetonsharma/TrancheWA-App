package com.tranche.bakery;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end scenario tests for the WhatsApp ordering flow.
 *
 * Each test drives FlowEngine via send()/sendImage() helpers,
 * backed by a real Postgres TestContainer and a mocked WhatsAppClient.
 * No actual WhatsApp API calls are made.
 */
class OrderFlowTest extends FlowScenarioBase {

    // ── 1. Happy path: single order from hi → payment screenshot ─────────────

    @Test
    void happyPath_singleOrder_endToEnd() {
        Long orderId = driveToPaymentQr();

        sendImage("media-test-001");

        assertState("IDLE");
        assertOrderStatus(orderId, OrderStatus.CONFIRMED);
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
        send("use_address");        // ORDER_CONFIRM
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
}
