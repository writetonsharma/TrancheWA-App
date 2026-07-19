package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AddItemToOrderAction implements FlowAction {

    private final OrderService orderService;

    @Value("${bakery.order.per-order-item-limit:3}")
    private int perOrderItemLimit;

    @Override
    public String getName() { return "ADD_ITEM_TO_ORDER"; }

    @Override
    public void execute(ActionContext ctx) {
        String itemIdStr   = ctx.contextValue("itemId");
        String quantityStr = ctx.getInput();

        if (itemIdStr == null) {
            log.warn("ADD_ITEM_TO_ORDER: no itemId in context for customer {}", ctx.getCustomer().getPhone());
            return;
        }

        int quantity;
        try {
            quantity = Integer.parseInt(quantityStr.trim());
        } catch (NumberFormatException e) {
            quantity = 1;
        }

        Order order = orderService.getOrCreateDraft(ctx.getCustomer(), ctx.getConversation());

        // Save orderId to context so subsequent actions can reference it
        ctx.context().put("orderId", order.getId().toString());

        // Cap the number of items for a delivery day. The count includes any same-date
        // unpaid order that will merge into this cart, so the merged total can never
        // exceed the limit. If this add would push it past the cap, do not add it and
        // route the customer to the bulk-order handoff.
        int currentQty = orderService.committedItemCountForDate(
                ctx.getCustomer().getId(), order.getId(), order.getDeliveryDate());
        if (currentQty + quantity > perOrderItemLimit) {
            log.info("Add of {} x {} would exceed per-order limit {} (cart has {}) for customer {} -> bulk limit",
                    itemIdStr, quantity, perOrderItemLimit, currentQty, ctx.getCustomer().getPhone());
            ctx.setRedirectState("ORDER_BULK_LIMIT");
            return;
        }

        orderService.addItem(order, Long.parseLong(itemIdStr), quantity);
        log.info("Added item {} x {} to order {} for customer {}",
                itemIdStr, quantity, order.getId(), ctx.getCustomer().getPhone());
    }
}
