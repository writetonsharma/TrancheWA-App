package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
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
    private final QrCodeService qrCodeService;
    private final WhatsAppClient whatsAppClient;

    @Value("${bakery.payment.upi-id}")
    private String upiId;

    @Value("${bakery.payment.upi-name}")
    private String upiName;

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

        BigDecimal amount = order.getTotalAmount();
        String note = "Tranche Bakery Order #" + order.getId();

        try {
            byte[] qrPng = qrCodeService.generateUpiQrPng(upiId, upiName, amount, note);
            String mediaId = whatsAppClient.uploadMedia(qrPng, "payment-qr.png");
            String caption = String.format(
                    "💳 *Please pay ₹%.0f to complete your order.*%n%n" +
                    "Scan the QR code above with any UPI app, or pay manually to *%s*.%n%n" +
                    "Once paid, please share a screenshot here and we'll confirm your order promptly. 🙏",
                    amount, upiId);
            whatsAppClient.sendImage(ctx.getCustomer().getPhone(), mediaId, caption);
        } catch (Exception e) {
            log.warn("QR upload failed, falling back to text payment prompt: {}", e.getMessage());
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    String.format("💳 *Please pay ₹%.0f to complete your order.*%n%n" +
                            "*UPI ID:* %s%n%n" +
                            "Once paid, please share a screenshot here and we'll confirm your order promptly. 🙏",
                            amount, upiId));
        }
    }
}
