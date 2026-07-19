package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Entry guard for the category browse. If the customer's cart is already at the
 * per-order item limit, skip straight to the bulk-order handoff instead of
 * letting them browse and pick an item only to be blocked at add time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GuardCartLimitAction implements FlowAction {

    private final OrderService orderService;

    @Value("${bakery.order.per-order-item-limit:3}")
    private int perOrderItemLimit;

    @Override
    public String getName() { return "GUARD_CART_LIMIT"; }

    @Override
    public void execute(ActionContext ctx) {
        int currentQty = orderService.committedItemCountForCurrentDraft(ctx.getCustomer());
        if (currentQty >= perOrderItemLimit) {
            log.info("Cart already at limit {} for customer {} -> bulk limit",
                    perOrderItemLimit, ctx.getCustomer().getPhone());
            ctx.setRedirectState("ORDER_BULK_LIMIT");
        }
    }
}
