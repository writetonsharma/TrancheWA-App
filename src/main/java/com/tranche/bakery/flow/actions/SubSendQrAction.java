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

    @Value("${bakery.support.whatsapp}")
    private String supportPhone;

    private String supportLine() {
        return "Trouble paying? Message or call us on " + supportPhone + ".";
    }

    /** An unpaid link for the same amount is still good — re-send it rather than mint a second one. */
    private String reusableLinkUrl(Subscription sub, BigDecimal amount) {
        return sub.getGatewayLinkUrl() != null
                && sub.getGatewayChargedAmount() != null
                && sub.getGatewayChargedAmount().compareTo(amount) == 0
                        ? sub.getGatewayLinkUrl()
                        : null;
    }

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

        // Gateway path: a hosted payment link, activated by the webhook. No fall back to the QR flow —
        // two live payment routes for one subscription invites a double payment.
        if (razorpayService.isGatewayMode()) {
            String linkUrl = reusableLinkUrl(sub, amount);
            if (linkUrl == null) {
                // Only reached when the amount changed, so the old link would collect the wrong sum.
                if (sub.getGatewayLinkId() != null) {
                    try {
                        razorpayService.cancelPaymentLink(sub.getGatewayLinkId());
                    } catch (Exception e) {
                        log.warn("Could not cancel superseded link {} for subscription {} \u2014 {}",
                                sub.getGatewayLinkId(), sub.getId(), e.getMessage());
                    }
                }
                // reference_id must be unique per link, so a re-issue cannot reuse the plain sub ref.
                String referenceId = sub.getGatewayLinkId() == null
                        ? "SUB-" + sub.getId()
                        : "SUB-" + sub.getId() + "-" + java.time.Instant.now().getEpochSecond();
                try {
                    RazorpayService.PaymentLink link = razorpayService.createPaymentLink(
                            amount, note, referenceId,
                            ctx.getCustomer().getName(), phone,
                            java.util.Map.of("kind", "SUBSCRIPTION", "subscriptionId", String.valueOf(sub.getId())),
                            null);

                    // Recorded before the customer can pay, so the webhook can reconcile and
                    // amount-verify before activating.
                    sub.setGatewayLinkId(link.id());
                    sub.setGatewayLinkUrl(link.shortUrl());
                    sub.setGatewayChargedAmount(amount);
                    subscriptionRepository.save(sub);
                    linkUrl = link.shortUrl();
                    log.info("Created Razorpay payment link {} for subscription {}", link.id(), sub.getId());
                } catch (Exception e) {
                    log.error("Razorpay link failed for subscription {}: {}", sub.getId(), e.getMessage());
                    alertService.raise("PAYMENT_LINK_FAILED",
                            "Could not create a Razorpay link for subscription " + sub.getId() + ": " + e.getMessage(),
                            null, phone);
                    whatsAppClient.sendText(phone, String.format(
                            "We couldn't generate the payment link for your %s subscription just now.%n%n%s",
                            sub.getPlanName(), supportLine()));
                    return;
                }
            }

            whatsAppClient.sendText(phone, String.format(
                    "*%s subscription \u2014 \u20b9%s*%n%n\uD83D\uDC49 Tap to pay securely (UPI, card or netbanking):%n%s%n%n"
                            + "Your subscription activates automatically once payment is received. \uD83E\uDD56%n%n%s",
                    sub.getPlanName(), amount.stripTrailingZeros().toPlainString(), linkUrl, supportLine()));
            whatsAppClient.sendButtons(phone,
                    "Changed your mind? You can cancel below.",
                    List.of(new WhatsAppMessage.Button("sub_cancel", "Cancel")));
            return;
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
