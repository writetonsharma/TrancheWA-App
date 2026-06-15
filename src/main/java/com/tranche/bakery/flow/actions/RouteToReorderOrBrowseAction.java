package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RouteToReorderOrBrowseAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "ROUTE_TO_REORDER_OR_BROWSE"; }

    @Override
    public void execute(ActionContext ctx) {
        boolean hasPreviousOrder = orderRepository
                .findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(
                        ctx.getCustomer().getId(),
                        List.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING, OrderStatus.COMPLETED))
                .isPresent();

        ctx.setRedirectState(hasPreviousOrder ? "ORDER_SUGGEST_REORDER" : "ORDER_SELECT_CATEGORY");
    }
}
