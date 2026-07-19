package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Discards the customer's current draft cart. Used when the customer declines to
 * check out at the per-order item cap, so the cart is emptied and they are not
 * trapped in the bulk-limit loop by the category-browse guard next time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiscardDraftAction implements FlowAction {

    private final OrderService orderService;

    @Override
    public String getName() { return "DISCARD_DRAFT"; }

    @Override
    public void execute(ActionContext ctx) {
        orderService.cancelDraftIfExists(ctx.getCustomer());
        log.info("Draft cart discarded for customer {}", ctx.getCustomer().getPhone());
    }
}
