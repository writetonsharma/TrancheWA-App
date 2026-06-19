package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ShowOrderStatusAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final WhatsAppClient whatsAppClient;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);

    @Override
    public String getName() { return "SHOW_ORDER_STATUS"; }

    @Override
    public void execute(ActionContext ctx) {
        Long customerId = ctx.getCustomer().getId();

        List<Order> active = new ArrayList<>();
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.IN_BAKING));
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.CONFIRMED));
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PENDING_CONFIRMATION));

        if (active.isEmpty()) {
            // Fall back to most recent completed order
            orderRepository.findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(
                    customerId, List.of(OrderStatus.COMPLETED))
                    .ifPresentOrElse(
                            o -> whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                                    "🎉 *Last order delivered!*\n\nOrder *" + ref(o) + "* was delivered. " +
                                    "Send *hi* to place your next order."),
                            () -> whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                                    "You don't have any active orders at the moment.\n\nSend *hi* to place a new order. 🥖"));
            return;
        }

        if (active.size() == 1) {
            Order o = active.get(0);
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "*Order " + ref(o) + dateLine(o) + "*\n\n" + statusLine(o.getStatus()));
            return;
        }

        // Multiple active orders — show a list
        StringBuilder sb = new StringBuilder("*Your active orders:*\n\n");
        for (Order o : active) {
            sb.append("• *").append(ref(o)).append("*").append(dateLine(o))
              .append(" — ").append(statusEmoji(o.getStatus()))
              .append("\n");
        }
        whatsAppClient.sendText(ctx.getCustomer().getPhone(), sb.toString().trim());
    }

    private String ref(Order o) {
        return o.getOrderNumber() != null ? o.getOrderNumber() : "#" + o.getId();
    }

    private String dateLine(Order o) {
        return o.getDeliveryDate() != null ? " · " + o.getDeliveryDate().format(DATE_FMT) : "";
    }

    private String statusLine(OrderStatus s) {
        return switch (s) {
            case PENDING_CONFIRMATION -> "⏳ *Awaiting payment.*\nPlease complete payment and share the screenshot to confirm your order.";
            case CONFIRMED            -> "✅ *Confirmed.*\nPayment received. We'll bake fresh and deliver between 6–8 AM.";
            case IN_BAKING            -> "🔥 *Baking right now!*\nFreshness in progress — delivery follows shortly.";
            default                   -> "Being processed. We'll update you shortly.";
        };
    }

    private String statusEmoji(OrderStatus s) {
        return switch (s) {
            case PENDING_CONFIRMATION -> "⏳ awaiting payment";
            case CONFIRMED            -> "✅ confirmed";
            case IN_BAKING            -> "🔥 baking now";
            default                   -> s.name().toLowerCase();
        };
    }
}
