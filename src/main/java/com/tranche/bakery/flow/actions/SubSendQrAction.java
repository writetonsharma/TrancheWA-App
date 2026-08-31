package com.tranche.bakery.flow.actions;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.payment.QrCodeService;
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

    @Value("${bakery.payment.upi-id}")
    private String upiId;

    @Value("${bakery.payment.upi-name}")
    private String upiName;

    @Value("${bakery.payment.test-mode:false}")
    private boolean testMode;

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

        BigDecimal amount = testMode
                ? BigDecimal.valueOf(1.0 + (int) (Math.random() * 99) / 100.0).setScale(2, RoundingMode.HALF_UP)
                : sub.getUpfrontAmount();
        String note = ("Tranche Bakery Subscription " + sub.getId())
                .replaceAll("[^A-Za-z0-9 ]", " ").replaceAll(" +", " ").trim();

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
