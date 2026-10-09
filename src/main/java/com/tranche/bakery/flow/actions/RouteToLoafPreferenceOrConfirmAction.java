package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RouteToLoafPreferenceOrConfirmAction implements FlowAction {

    private static final String LOAVES_CATEGORY = "Loaves";

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "ROUTE_TO_LOAF_PREFERENCE_OR_CONFIRM"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderId = ctx.contextValue("orderId");
        boolean hasLoaves = orderId != null
                && orderItemRepository.existsByOrderIdAndCategoryName(Long.parseLong(orderId), LOAVES_CATEGORY);
        if (!hasLoaves) {
            ctx.setRedirectState("ORDER_CONFIRM");
            return;
        }
        Order order = orderRepository.findById(Long.parseLong(orderId)).orElse(null);
        if (order == null || order.getLoafPreference() != null) {
            // No order, or slice already chosen on this draft — straight to the summary.
            ctx.setRedirectState("ORDER_CONFIRM");
            return;
        }
        // Returning customer: reuse the slice preference from their last order with loaves and skip the question.
        var last = orderRepository
                .findTopByCustomerIdAndIdNotAndSubscriptionIdIsNullAndLoafPreferenceIsNotNullOrderByCreatedAtDesc(
                        ctx.getCustomer().getId(), order.getId());
        if (last.isPresent()) {
            order.setLoafPreference(last.get().getLoafPreference());
            orderRepository.save(order);
            ctx.setRedirectState("ORDER_CONFIRM");
        } else {
            ctx.setRedirectState("LOAF_PREFERENCE");
        }
    }
}
