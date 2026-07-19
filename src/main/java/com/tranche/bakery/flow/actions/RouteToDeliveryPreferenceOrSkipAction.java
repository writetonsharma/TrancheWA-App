package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Skips the delivery-preference step when the current order already has one set.
 * This prevents the customer from being asked repeatedly when adding more items
 * to the same draft.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RouteToDeliveryPreferenceOrSkipAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "ROUTE_TO_DELIVERY_PREFERENCE_OR_SKIP"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) return;

        orderRepository.findById(Long.parseLong(orderIdStr)).ifPresent(order -> {
            if (order.getDeliveryPreference() != null) {
                log.debug("Order {} already has delivery preference {}, skipping",
                        order.getId(), order.getDeliveryPreference());
                ctx.setRedirectState("LOAF_PREFERENCE_GATE");
            }
        });
    }
}
