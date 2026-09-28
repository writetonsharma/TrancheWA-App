package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class AddItemToOrderAction implements FlowAction {

    private final OrderService orderService;
    private final DeliveryRules deliveryRules;

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

        // A cart may fill the whole day's bake but no more. Drafts don't reserve capacity, so the
        // draft's own items are counted on top of what the rest of the day has already taken.
        LocalDate date = order.getDeliveryDate();
        long room = date == null
                ? deliveryRules.getDailyCapacity()
                : deliveryRules.remainingCapacity(date);
        int draftQty = orderService.currentDraftItemCount(ctx.getCustomer());
        if (draftQty + quantity > room) {
            log.info("Add of {} x {} would exceed the day's remaining capacity {} (cart has {}) for customer {} -> bulk limit",
                    itemIdStr, quantity, room, draftQty, ctx.getCustomer().getPhone());
            ctx.setRedirectState("ORDER_BULK_LIMIT");
            return;
        }

        orderService.addItem(order, Long.parseLong(itemIdStr), quantity);
        log.info("Added item {} x {} to order {} for customer {}",
                itemIdStr, quantity, order.getId(), ctx.getCustomer().getPhone());
    }
}
