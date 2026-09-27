package com.tranche.bakery.flow.actions;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.payment.PaymentTestMode;
import com.tranche.bakery.payment.QrCodeService;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Sends the UPI QR for the subscription's prepaid upfront amount. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubSendQrAction implements FlowAction {

    private final SubscriptionRepository subscriptionRepository;
    private final QrCodeService qrCodeService;
    private final WhatsAppClient whatsAppClient;
    private final AlertService alertService;
    private final PaymentTestMode paymentTestMode;
    private final RazorpayService razorpayService;

    @Value("${bakery.payment.upi-id}")
    private String upiId;

    @Value("${bakery.payment.upi-name}")
    private String upiName;

    @Value("${bakery.payment.provider:UPI_QR}")
    private String paymentProvider;

    @Override
    public String getName() { return "SUB_SEND_QR"; }

    @Override
    public void execute(ActionContext ctx) {
        String phone = ctx.getCustomer().getPhone();
        String subIdStr = ctx.contextValue("subId");
        Subscription sub = subIdStr != null ? subscriptionRepository.findById(Long.parseLong(subIdStr)).orElse(null) : null;
        if (sub == null) {
            whatsAppClient.sendText(phone, "We couldn't find your subscription. Send *hi* to start again.");
            return;
        }

        BigDecimal amount = paymentTestMode.amountFor(phone, sub.getUpfrontAmount());
        String note = ("Tranche Bakery Subscription " + sub.getId())
                .replaceAll("[^A-Za-z0-9 ]", " ").replaceAll(" +", " ").trim();

        // Razorpay path: send a hosted payment link; the webhook activates the subscription (no screenshot).
        if ("RAZORPAY".equalsIgnoreCase(paymentProvider) && razorpayService.isConfigured()) {
            try {
                RazorpayService.PaymentLink link = razorpayService.createPaymentLink(
                        amount, note, "SUB-" + sub.getId(),
                        ctx.getCustomer().getName(), phone,
                        java.util.Map.of("kind", "SUBSCRIPTION", "subscriptionId", String.valueOf(sub.getId())));
                whatsAppClient.sendText(phone, String.format(
                        "*%s subscription \u2014 \u20b9%s*%n%n\uD83D\uDC49 Tap to pay securely (UPI, card or netbanking):%n%s%n%nYour subscription activates automatically once payment is received. \uD83E\uDD56",
                        sub.getPlanName(), amount.stripTrailingZeros().toPlainString(), link.shortUrl()));
                whatsAppClient.sendButtons(phone,
                        "Changed your mind? You can cancel below.",
                        List.of(new WhatsAppMessage.Button("sub_cancel", "Cancel")));
                log.info("Sent Razorpay payment link {} for subscription {}", link.id(), sub.getId());
                return;
            } catch (Exception e) {
                log.error("Razorpay link failed for subscription {} \u2014 falling back to UPI QR: {}", sub.getId(), e.getMessage());
                // fall through to the UPI QR flow below
            }
        }

        try {
            byte[] qrPng = qrCodeService.generateUpiQrPng(upiId, upiName, amount, note);
            if (qrPng.length == 0) throw new IllegalStateException("QR PNG is empty");
            String mediaId = whatsAppClient.uploadMedia(qrPng, "subscription-qr.png");
            String caption = String.format(
                    "*%s subscription — ₹%s*%n%nScan the QR with any UPI app, or pay to *%s*.%n%n" +
                    "📸 *Important — after paying, share the payment screenshot here.* That's the final step to activate your subscription.",
                    sub.getPlanName(), amount.stripTrailingZeros().toPlainString(), upiId);
            whatsAppClient.sendImage(phone, mediaId, caption);
        } catch (Exception e) {
            log.error("Subscription QR failed for {}: {}", sub.getId(), e.getMessage(), e);
            alertService.raise("QR_FAILURE",
                    "Subscription QR failed for subscription " + sub.getId() + ": " + e.getMessage(), null, phone);
            whatsAppClient.sendText(phone, String.format(
                    "*%s subscription — please pay ₹%s.*%n%n*UPI ID:* %s%n%n" +
                    "📸 *After paying, share the payment screenshot here* to activate your subscription.",
                    sub.getPlanName(), amount.stripTrailingZeros().toPlainString(), upiId));
        }

        whatsAppClient.sendButtons(phone,
                "Once paid, share the screenshot here to activate. Changed your mind? You can cancel below.",
                List.of(new WhatsAppMessage.Button("sub_cancel", "Cancel")));
    }
}
