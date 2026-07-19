package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
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
        String phone = ctx.getCustomer().getPhone();

        List<Order> active = new ArrayList<>();
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.IN_BAKING));
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.CONFIRMED));
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PENDING_CONFIRMATION));

        if (active.isEmpty()) {
            orderRepository.findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(
                    customerId, List.of(OrderStatus.COMPLETED))
                    .ifPresentOrElse(
                            o -> whatsAppClient.sendText(phone,
                                    "🎉 *Last order delivered!*\n\nOrder *" + ref(o) + "* was delivered. " +
                                    "Send *hi* to place your next order."),
                            () -> whatsAppClient.sendText(phone,
                                    "You don't have any active orders at the moment.\n\nSend *hi* to place a new order. 🥖"));
            return;
        }

        List<Order> pendingPayment = active.stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING_CONFIRMATION)
                .toList();

        if (active.size() == 1) {
            Order o = active.get(0);
            String text = "*Order " + ref(o) + dateLine(o) + "*\n\n" + statusLine(o.getStatus());
            if (o.getStatus() == OrderStatus.PENDING_CONFIRMATION) {
                whatsAppClient.sendButtons(phone, text, List.of(
                        new WhatsAppMessage.Button("cancel_" + o.getId(), "Cancel Order")));
            } else {
                whatsAppClient.sendText(phone, text);
            }
            return;
        }

        StringBuilder sb = new StringBuilder("*Your active orders:*\n\n");
        int idx = 0;
        for (Order o : active) {
            idx++;
            sb.append(idx).append(". *").append(ref(o)).append("*").append(dateLine(o))
              .append(" — ").append(statusEmoji(o.getStatus()))
              .append("\n");
        }

        // WhatsApp caps interactive button titles at 20 characters. A full order
        // reference ("Cancel TRB-20260719-AB3K" = 24 chars) makes the Cloud API reject
        // the entire message, so the status would silently never reach the customer.
        // Label each cancel button by the order's position in the list above; the button
        // payload still carries the real order id for the global cancel_<id> handler.
        if (!pendingPayment.isEmpty() && pendingPayment.size() <= 3) {
            List<WhatsAppMessage.Button> buttons = new ArrayList<>();
            int pos = 0;
            for (Order o : active) {
                pos++;
                if (o.getStatus() == OrderStatus.PENDING_CONFIRMATION) {
                    buttons.add(new WhatsAppMessage.Button("cancel_" + o.getId(), "Cancel #" + pos));
                }
            }
            sb.append("\n_Tap a button below to cancel an order awaiting payment._");
            whatsAppClient.sendButtons(phone, sb.toString().trim(), buttons);
        } else {
            whatsAppClient.sendText(phone, sb.toString().trim());
        }
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
