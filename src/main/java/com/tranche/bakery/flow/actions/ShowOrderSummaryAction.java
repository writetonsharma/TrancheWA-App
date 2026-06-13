package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShowOrderSummaryAction implements FlowAction {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SHOW_ORDER_SUMMARY"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(), "No active order found. Send *hi* to start over.");
            return;
        }

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(), "Order not found. Send *hi* to start over.");
            return;
        }

        String summary = orderService.formatSummary(order);
        if ("Your order is empty.".equals(summary)) {
            whatsAppClient.sendButtons(
                    ctx.getCustomer().getPhone(),
                    "Your basket is empty. Please cancel and send *hi* to start a fresh order.",
                    List.of(new WhatsAppMessage.Button("cancel", "Cancel"))
            );
            return;
        }
        whatsAppClient.sendButtons(
                ctx.getCustomer().getPhone(),
                summary + "\n\nShall we place this order?",
                List.of(
                        new WhatsAppMessage.Button("confirm", "Confirm Order"),
                        new WhatsAppMessage.Button("cancel",  "Cancel")
                )
        );
    }
}
