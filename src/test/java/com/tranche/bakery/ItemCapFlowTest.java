package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;

/**
 * Scenarios for the per-order item cap (3 items) that replaced the old 15+ dead-end.
 * A cart may never exceed 3 items; attempts to exceed route the customer to the
 * ORDER_BULK_LIMIT handoff instead of silently trapping them.
 */
class ItemCapFlowTest extends FlowScenarioBase {

    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderRepository orderRepository;

    private int cartQty() {
        return orderRepository
                .findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customer.getId(), OrderStatus.DRAFT)
                .map(o -> orderItemRepository.sumQuantityByOrderId(o.getId()))
                .orElse(0);
    }

    // Quantity picker no longer accepts 4 or 5.
    @Test
    void quantityPicker_rejectsFour() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        send("hi");
        send("order");
        send(catId);
        send(itemId);
        assertState("ORDER_SELECT_QUANTITY");

        send("4");
        assertState("ORDER_SELECT_QUANTITY");
        assertThat(cartQty()).isEqualTo(0);
    }

    // Adding a quantity that would exceed 3 is refused; nothing is added, and the
    // customer lands on the bulk-order handoff.
    @Test
    void exceedingThree_routesToBulkLimit_withoutAdding() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        send("hi");
        send("order");
        send(catId);
        send(itemId);
        send("2");
        assertState("ORDER_ADD_MORE");
        assertThat(cartQty()).isEqualTo(2);

        // Add another of the same item, quantity 2 -> would be 4 -> blocked.
        send("add_item");
        send(catId);
        send(itemId);
        send("2");

        assertState("ORDER_BULK_LIMIT");
        assertThat(cartQty()).as("blocked add must not change the cart").isEqualTo(2);
    }

    // A cart already at the limit short-circuits the browse straight to the handoff.
    @Test
    void fullCart_addAnother_guardsToBulkLimit() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        send("hi");
        send("order");
        send(catId);
        send(itemId);
        send("3");
        assertState("ORDER_ADD_MORE");
        assertThat(cartQty()).isEqualTo(3);

        send("add_item");
        assertState("ORDER_BULK_LIMIT");
    }

    // From the handoff, "Place Order" proceeds to checkout with the current cart.
    @Test
    void bulkLimit_placeOrder_proceedsToDate() {
        driveToBulkLimit();

        send("checkout");
        assertState("ORDER_SELECT_DATE");
        assertThat(cartQty()).isEqualTo(3);
    }

    // From the handoff, "No, thanks" ends the flow at the main menu.
    @Test
    void bulkLimit_noThanks_returnsToMenu() {
        driveToBulkLimit();

        send("done");
        assertState("MAIN_MENU");
    }

    private void driveToBulkLimit() {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);
        send("hi");
        send("order");
        send(catId);
        send(itemId);
        send("3");
        send("add_item");
        assertState("ORDER_BULK_LIMIT");
    }
}
