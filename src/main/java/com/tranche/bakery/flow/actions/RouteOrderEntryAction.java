package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;

import lombok.RequiredArgsConstructor;

/**
 * Entry point for the Order button: subscription-eligible (F&F) customers get the one-time vs
 * subscription choice; everyone else goes straight to the normal ordering flow.
 */
@Component
@RequiredArgsConstructor
public class RouteOrderEntryAction implements FlowAction {

    @Override
    public String getName() { return "ROUTE_ORDER_ENTRY"; }

    @Override
    public void execute(ActionContext ctx) {
        if (ctx.getCustomer() != null && ctx.getCustomer().isSubscriptionEligible()) {
            ctx.setRedirectState("ORDER_CHOICE");
        } else {
            ctx.setRedirectState("ORDER_GATE");
        }
    }
}
