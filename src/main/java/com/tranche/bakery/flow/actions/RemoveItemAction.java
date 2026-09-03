package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Removes the chosen cart line (input id "remove_&lt;orderItemId&gt;"); empties → back to browsing. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemoveItemAction implements FlowAction {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "REMOVE_ITEM"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderId = ctx.contextValue("orderId");
        Order order = orderId != null ? orderRepository.findById(Long.parseLong(orderId)).orElse(null) : null;
        String input = ctx.getInput();
        if (order != null && input != null && input.startsWith("remove_")) {
            try {
                orderService.removeItem(order, Long.parseLong(input.substring("remove_".length())));
            } catch (NumberFormatException ignored) {
                // not a valid remove id — ignore and just re-show the cart
            }
        }

        if (order != null && orderItemRepository.findAllByOrderId(order.getId()).isEmpty()) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(), "Your cart is now empty. Let's add something. 🥖");
            ctx.setRedirectState("ORDER_SELECT_CATEGORY");
        }
    }
}
