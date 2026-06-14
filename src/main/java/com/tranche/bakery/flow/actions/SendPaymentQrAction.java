package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.QrCodeService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class SendPaymentQrAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final QrCodeService qrCodeService;
    private final WhatsAppClient whatsAppClient;

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
                    "We couldn't find your order. Please send *hi* to start over.");
            return;
        }

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "We couldn't find your order. Please send *hi* to start over.");
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
            String mediaId = whatsAppClient.uploadMedia(qrPng, "payment-qr.png");
            String caption = String.format(
                    "💳 *Please pay ₹%.2f to complete your order.*%n%n" +
                    "Scan the QR code above with any UPI app, or pay manually to *%s*.%n%n" +
                    "Once paid, please share a screenshot here and we'll confirm your order promptly. 🙏",
                    amount, upiId);
            whatsAppClient.sendImage(ctx.getCustomer().getPhone(), mediaId, caption);
        } catch (Exception e) {
            log.error("Payment QR flow failed for order {}: {}", order.getId(), e.getMessage(), e);
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    String.format("💳 *Please pay ₹%.2f to complete your order.*%n%n" +
                            "*UPI ID:* %s%n%n" +
                            "Once paid, please share a screenshot here and we'll confirm your order promptly. 🙏",
                            amount, upiId));
        }
    }
}
