package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;

import lombok.RequiredArgsConstructor;

/** Subscription-flow address gate: collect a new address, or confirm the saved one, before the summary. */
@Component
@RequiredArgsConstructor
public class SubRouteToAddressAction implements FlowAction {

    @Override
    public String getName() { return "SUB_ROUTE_TO_ADDRESS"; }

    @Override
    public void execute(ActionContext ctx) {
        Customer customer = ctx.getCustomer();
        if (customer.getDeliveryAddress() == null || customer.getDeliveryAddress().isBlank()) {
            ctx.setRedirectState("SUB_ADDRESS_COLLECT");
        } else {
            ctx.setRedirectState("SUB_ADDRESS_CONFIRM");
        }
    }
}
