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
        // Date-first starts the delivery-date step BEFORE any item is added, so a stale
        // orderId/deliveryDate left in context by a previous (completed) order would wrongly
        // filter the date list and menu. Clear them here so a new order starts clean; the
        // reorder branch repopulates orderId via CopyLastOrderAction, and SaveDeliveryDate
        // re-resolves the draft from the database (the source of truth) when the date is chosen.
        var context = ctx.getConversation().getContext();
        context.remove("orderId");
        context.remove("deliveryDate");
        context.remove("categoryId");

        boolean hasPreviousOrder = orderRepository
                .findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(
                        ctx.getCustomer().getId(),
                        List.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING, OrderStatus.COMPLETED))
                .isPresent();

        // Date-first: a brand-new order asks for the delivery day before browsing,
        // so the menu can be filtered to what we actually bake for that morning.
        ctx.setRedirectState(hasPreviousOrder ? "ORDER_SUGGEST_REORDER" : "ORDER_SELECT_DATE");
    }
}
