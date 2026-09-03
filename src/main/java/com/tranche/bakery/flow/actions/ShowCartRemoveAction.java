package com.tranche.bakery.flow.actions;

import java.util.ArrayList;
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

/** Lists the cart items as removable rows (id remove_&lt;orderItemId&gt;) plus a Back row. */
@Component
@RequiredArgsConstructor
public class ShowCartRemoveAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SHOW_CART_REMOVE"; }

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

        List<WhatsAppMessage.Row> rows = new ArrayList<>();
        for (OrderItem it : items) {
            if (rows.size() >= 9) break;   // leave a slot for Back within WhatsApp's 10-row cap
            String title = it.getMenuItem().getName() + " \u00d7 " + it.getQuantity();
            if (title.length() > 24) title = title.substring(0, 23) + "\u2026";
            rows.add(new WhatsAppMessage.Row("remove_" + it.getId(), title));
        }
        rows.add(new WhatsAppMessage.Row("cart_back", "\u2b05 Back to cart"));

        whatsAppClient.sendList(phone, "Tap the item you'd like to remove:", "Choose item",
                List.of(new WhatsAppMessage.Section("Remove an item", rows)));
    }
}
