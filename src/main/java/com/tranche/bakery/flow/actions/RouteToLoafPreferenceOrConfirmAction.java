package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RouteToLoafPreferenceOrConfirmAction implements FlowAction {

    private static final String LOAVES_CATEGORY = "Loaves";

    private final OrderItemRepository orderItemRepository;

    @Override
    public String getName() { return "ROUTE_TO_LOAF_PREFERENCE_OR_CONFIRM"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderId = ctx.contextValue("orderId");
        boolean hasLoaves = orderId != null
                && orderItemRepository.existsByOrderIdAndCategoryName(Long.parseLong(orderId), LOAVES_CATEGORY);
        ctx.setRedirectState(hasLoaves ? "LOAF_PREFERENCE" : "ORDER_CONFIRM");
    }
}
