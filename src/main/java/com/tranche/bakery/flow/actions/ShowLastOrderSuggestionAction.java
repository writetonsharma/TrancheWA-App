package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.*;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ShowLastOrderSuggestionAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SHOW_LAST_ORDER_SUGGESTION"; }

    @Override
    public void execute(ActionContext ctx) {
        Optional<Order> last = orderRepository.findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(
                ctx.getCustomer().getId(),
                List.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING, OrderStatus.COMPLETED));

        if (last.isEmpty()) {
            ctx.setRedirectState("ORDER_SELECT_CATEGORY");
            return;
        }

        Order order = last.get();
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        if (items.isEmpty()) {
            ctx.setRedirectState("ORDER_SELECT_CATEGORY");
            return;
        }

        StringBuilder sb = new StringBuilder("🛒 *Your last order was:*\n\n");
        for (OrderItem item : items) {
            sb.append(String.format("• %s × %d\n", item.getMenuItem().getName(), item.getQuantity()));
        }
        sb.append("\nWould you like to reorder the same, or browse the menu?");

        whatsAppClient.sendButtons(ctx.getCustomer().getPhone(), sb.toString(),
                List.of(
                        new WhatsAppMessage.Button("reorder",     "Reorder"),
                        new WhatsAppMessage.Button("browse_menu", "Browse Menu")
                ));
    }
}
