package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ShowOrderStatusAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SHOW_ORDER_STATUS"; }

    @Override
    public void execute(ActionContext ctx) {
        Optional<Order> recent = orderRepository.findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(
                ctx.getCustomer().getId(),
                List.of(OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED,
                        OrderStatus.IN_BAKING, OrderStatus.COMPLETED));

        if (recent.isEmpty()) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "You don't have any active orders at the moment. Send *hi* to place a new order. 🥖");
            return;
        }

        Order order = recent.get();
        String ref = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
        String statusMsg = switch (order.getStatus()) {
            case PENDING_CONFIRMATION -> "⏳ *Awaiting payment.*\nWe've sent you a payment QR — please complete payment and share the screenshot to confirm your order.";
            case CONFIRMED            -> "✅ *Order confirmed.*\nYour payment has been received. We'll bake your order fresh and deliver it next morning between 6–8 AM.";
            case IN_BAKING            -> "🔥 *Your order is being baked right now!*\nFreshness is in progress — delivery will follow shortly.";
            case COMPLETED            -> "🎉 *Delivered!*\nWe hope you enjoyed your bread. Send *hi* to place your next order.";
            default                   -> "Your order is being processed. We'll update you shortly.";
        };

        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "*Order " + ref + "*\n\n" + statusMsg);
    }
}
