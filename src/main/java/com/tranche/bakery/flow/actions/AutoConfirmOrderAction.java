package com.tranche.bakery.flow.actions;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoConfirmOrderAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final WhatsAppClient whatsAppClient;
    private final AlertService alertService;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);

    @Override
    public String getName() { return "AUTO_CONFIRM_ORDER"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) return;

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) return;

        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        paymentRepository.findByOrder(order).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.SCREENSHOT_VERIFIED);
            paymentRepository.save(payment);
        });

        String orderNumber = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
        String datePart = order.getDeliveryDate() != null
                ? " for *" + order.getDeliveryDate().format(DATE_FMT) + "*"
                : "";

        StringBuilder msg = new StringBuilder();
        msg.append("✅ *Payment received — order confirmed!*\n\n");
        msg.append("Order *").append(orderNumber).append("*").append(datePart)
           .append(" is confirmed. We'll bake fresh and deliver between *6–8 AM*.\n\n");
        msg.append("Thank you for ordering from Tranché Bakery. 🥖");

        // Show any remaining pending orders so customer knows what still needs payment
        List<Order> remaining = orderRepository
                .findAllByCustomerIdAndStatus(ctx.getCustomer().getId(), OrderStatus.PENDING_CONFIRMATION);
        if (!remaining.isEmpty()) {
            msg.append("\n\n*Still awaiting payment:*\n");
            for (Order r : remaining) {
                String ref  = r.getOrderNumber() != null ? r.getOrderNumber() : "#" + r.getId();
                String date = r.getDeliveryDate() != null ? r.getDeliveryDate().format(DATE_FMT) : "date TBD";
                String amt  = r.getTotalAmount() != null
                        ? "₹" + r.getTotalAmount().setScale(0, RoundingMode.DOWN)
                        : "—";
                msg.append("• *").append(ref).append("* — ").append(date).append(" — ").append(amt).append("\n");
            }
            msg.append("\n_Send *hi* to pay for another order._");
        }

        whatsAppClient.sendText(ctx.getCustomer().getPhone(), msg.toString());

        // Notify admin
        String customerName = ctx.getCustomer().getName() != null
                ? ctx.getCustomer().getName() : ctx.getCustomer().getPhone();
        String amtPart = order.getTotalAmount() != null
                ? " · ₹" + order.getTotalAmount().setScale(0, RoundingMode.DOWN) : "";
        String dateStr = order.getDeliveryDate() != null
                ? order.getDeliveryDate().format(DATE_FMT) : "date TBD";
        alertService.raise("PAYMENT_RECEIVED",
                "📸 Payment received & order confirmed\n\n" +
                "Order: *" + orderNumber + "* · " + dateStr + amtPart + "\n" +
                "Customer: " + customerName + " (" + ctx.getCustomer().getPhone() + ")",
                order.getId(), ctx.getCustomer().getPhone());

        log.info("Order {} auto-confirmed for customer {}", orderNumber, ctx.getCustomer().getPhone());
    }
}
