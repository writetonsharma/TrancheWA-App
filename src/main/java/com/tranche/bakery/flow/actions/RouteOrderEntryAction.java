package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;

import lombok.RequiredArgsConstructor;

/**
 * Entry point for the Order button: everyone gets the one-time vs subscription choice so the
 * subscription is discoverable (subscriptions are open to all customers, not just F&F).
 */
@Component
@RequiredArgsConstructor
public class RouteOrderEntryAction implements FlowAction {

    @Override
    public String getName() { return "ROUTE_ORDER_ENTRY"; }

    @Override
    public void execute(ActionContext ctx) {
        ctx.setRedirectState("ORDER_CHOICE");
    }
}
