package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AddItemToOrderAction implements FlowAction {

    private final OrderService orderService;

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

        orderService.addItem(order, Long.parseLong(itemIdStr), quantity);
        log.info("Added item {} × {} to order {} for customer {}",
                itemIdStr, quantity, order.getId(), ctx.getCustomer().getPhone());
    }
}
