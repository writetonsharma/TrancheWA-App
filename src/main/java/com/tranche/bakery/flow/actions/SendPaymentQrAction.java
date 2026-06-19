package com.tranche.bakery.flow.actions;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.QrCodeService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SendPaymentQrAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final QrCodeService qrCodeService;
    private final WhatsAppClient whatsAppClient;
    private final AlertService alertService;

    @Value("${bakery.payment.upi-id}")
    private String upiId;

    @Value("${bakery.payment.upi-name}")
    private String upiName;

    @Value("${bakery.payment.test-mode:false}")
    private boolean testMode;

    @Override
    public String getName() { return "SEND_PAYMENT_QR"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "We couldn't find your order. Send *hi* to return to the main menu.");
            return;
        }

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "We couldn't find your order. Send *hi* to return to the main menu.");
            return;
        }

        BigDecimal amount = testMode
                ? BigDecimal.valueOf(1.0 + (int)(Math.random() * 99) / 100.0).setScale(2, java.math.RoundingMode.HALF_UP)
                : order.getTotalAmount();
        String note = "Tranche Bakery Order #" + order.getId();
        log.info("Sending payment QR for order {} amount {}", order.getId(), amount);

        try {
            Payment payment = paymentRepository.findByOrder(order).orElseGet(() -> {
                Payment p = new Payment();
                p.setOrder(order);
                p.setUpiId(upiId);
                return p;
            });
            payment.setAmount(amount);
            paymentRepository.save(payment);

            byte[] qrPng = qrCodeService.generateUpiQrPng(upiId, upiName, amount, note);
            log.info("QR PNG generated, {} bytes", qrPng.length);
            if (qrPng.length == 0) throw new IllegalStateException("QR PNG is empty — AWT rendering failed");

            payment.setQrImageData(qrPng);
            paymentRepository.save(payment);

            String mediaId = whatsAppClient.uploadMedia(qrPng, "payment-qr.png");
            log.info("Media uploaded, mediaId={}", mediaId);
            String caption = String.format(
                    "*Order %s — ₹%.2f*%n%nScan the QR code above with any UPI app, or pay manually to *%s*.%n%nOnce paid, please share the screenshot here. We'll confirm your order shortly.",
                    order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId(),
                    amount, upiId);
            whatsAppClient.sendImage(ctx.getCustomer().getPhone(), mediaId, caption);
            log.info("sendImage called for order {}", order.getId());
        } catch (Exception e) {
            log.error("Payment QR flow failed for order {}: {}", order.getId(), e.getMessage(), e);
            alertService.raise("QR_FAILURE",
                    "Payment QR failed for order " + order.getId() + ": " + e.getMessage(),
                    order.getId(), ctx.getCustomer().getPhone());
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    String.format("*Order %s — please complete payment of ₹%.2f.*%n%n" +
                            "*UPI ID:* %s%n%n" +
                            "Once paid, please share the screenshot here. We'll confirm your order shortly.",
                            order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId(),
                            amount, upiId));
        }

        try {
            whatsAppClient.sendButtons(ctx.getCustomer().getPhone(),
                    "Need to cancel this order? You can do so below.",
                    List.of(new WhatsAppMessage.Button("cancel_order", "Cancel Order")));
        } catch (Exception e) {
            log.error("Failed to send cancel button for order {}: {}", order.getId(), e.getMessage());
        }
    }
}
