package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RouteToAddressOrConfirmAction implements FlowAction {

    @Override
    public String getName() { return "ROUTE_TO_ADDRESS_OR_CONFIRM"; }

    @Override
    public void execute(ActionContext ctx) {
        if (ctx.getCustomer().getDeliveryAddress() == null) {
            log.debug("No delivery address for customer {}, routing to ADDRESS_COLLECT",
                    ctx.getCustomer().getPhone());
            ctx.setRedirectState("ADDRESS_COLLECT");
        } else {
            log.debug("Delivery address known for customer {}, routing to ORDER_CONFIRM",
                    ctx.getCustomer().getPhone());
            ctx.setRedirectState("ORDER_CONFIRM");
        }
    }
}
