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
import java.util.Comparator;
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
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PAYMENT_SCREENSHOT_RECEIVED));
        active.addAll(orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PAYMENT_REVIEW_REQUIRED));
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
                        new WhatsAppMessage.Button("paynow_" + o.getId(), "Pay Now"),
                        new WhatsAppMessage.Button("cancel_" + o.getId(), "Cancel Order")));
                ctx.setRedirectState("IDLE");
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

        // At most MAX_PENDING_ORDERS (3) unpaid orders can exist, so one "Pay" button each
        // fits WhatsApp's 3-button cap. Show them soonest-delivery first; tapping re-sends
        // that order's QR (which carries its own Cancel button), keeping payment one tap away.
        // Titles stay short ("Pay \u00b7 Tue, 21 Jul") so the Cloud API never 400s on the
        // 20-char cap and silently drops the whole status message.
        if (!pendingPayment.isEmpty() && pendingPayment.size() <= 3) {
            List<Order> payable = pendingPayment.stream()
                    .sorted(Comparator.comparing(Order::getDeliveryDate,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            List<WhatsAppMessage.Button> buttons = new ArrayList<>();
            for (Order o : payable) {
                String date  = o.getDeliveryDate() != null ? o.getDeliveryDate().format(DATE_FMT) : "";
                String title = date.isEmpty() ? "Pay " + ref(o) : "Pay \u00b7 " + date;
                buttons.add(new WhatsAppMessage.Button("paynow_" + o.getId(), title));
            }
            sb.append("\n_Tap an order below to pay. You can cancel from the payment screen._");
            whatsAppClient.sendButtons(phone, sb.toString().trim(), buttons);
            ctx.setRedirectState("IDLE");
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
            case PAYMENT_SCREENSHOT_RECEIVED -> "📸 *Payment screenshot received.*\nWe're verifying it and will message you when the order is confirmed.";
            case PAYMENT_REVIEW_REQUIRED -> "🔎 *Payment verification in progress.*\nWe need a little more time to check the payment and will message you when it is confirmed.";
            case CONFIRMED            -> "✅ *Confirmed.*\nPayment received. We'll bake fresh and deliver between 6–8 AM.";
            case IN_BAKING            -> "🔥 *Baking right now!*\nFreshness in progress — delivery follows shortly.";
            default                   -> "Being processed. We'll update you shortly.";
        };
    }

    private String statusEmoji(OrderStatus s) {
        return switch (s) {
            case PENDING_CONFIRMATION -> "⏳ awaiting payment";
            case PAYMENT_SCREENSHOT_RECEIVED -> "📸 payment verification";
            case PAYMENT_REVIEW_REQUIRED -> "🔎 payment review";
            case CONFIRMED            -> "✅ confirmed";
            case IN_BAKING            -> "🔥 baking now";
            default                   -> s.name().toLowerCase();
        };
    }
}
