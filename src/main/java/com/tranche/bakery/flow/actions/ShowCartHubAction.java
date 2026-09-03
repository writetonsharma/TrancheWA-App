package com.tranche.bakery.flow.actions;

import java.util.List;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;

/** Cart hub shown after each add: lists the cart and offers Add / Remove / Review. */
@Component
@RequiredArgsConstructor
public class ShowCartHubAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SHOW_CART"; }

    @Override
    public void execute(ActionContext ctx) {
        String phone = ctx.getCustomer().getPhone();
        String orderId = ctx.contextValue("orderId");
        Order order = orderId != null ? orderRepository.findById(Long.parseLong(orderId)).orElse(null) : null;
        List<OrderItem> items = order != null ? orderItemRepository.findAllByOrderId(order.getId()) : List.of();
        if (items.isEmpty()) {
            ctx.setRedirectState("ORDER_SELECT_CATEGORY");
            return;
        }

        StringBuilder sb = new StringBuilder("🛒 *Your cart*\n\n");
        for (OrderItem it : items) {
            sb.append("• ").append(it.getMenuItem().getName()).append(" × ").append(it.getQuantity()).append("\n");
        }
        sb.append("\nAdd another item, remove one, or review your order (with pricing).");

        whatsAppClient.sendButtons(phone, sb.toString(), List.of(
                new WhatsAppMessage.Button("add_item", "➕ Add Another"),
                new WhatsAppMessage.Button("remove_menu", "🗑 Remove Item"),
                new WhatsAppMessage.Button("view_order", "🧾 Review Order")));
    }
}
