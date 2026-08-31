package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** Stores the subscription payment screenshot and alerts the admin to verify + activate. */
@Component
@RequiredArgsConstructor
public class SubSaveScreenshotAction implements FlowAction {

    private final SubscriptionRepository subscriptionRepository;
    private final WhatsAppClient whatsAppClient;
    private final AlertService alertService;

    @Override
    public String getName() { return "SUB_SAVE_SCREENSHOT"; }

    @Override
    public void execute(ActionContext ctx) {
        String phone = ctx.getCustomer().getPhone();
        String subIdStr = ctx.contextValue("subId");
        Subscription sub = subIdStr != null ? subscriptionRepository.findById(Long.parseLong(subIdStr)).orElse(null) : null;
        if (sub == null) {
            whatsAppClient.sendText(phone, "We couldn't find your subscription. Send *hi* to start again.");
            return;
        }

        String mediaId = ctx.getRawMessage() != null
                ? ctx.getRawMessage().path("image").path("id").asText(null) : null;
        sub.setPaymentScreenshotMediaId(mediaId);
        subscriptionRepository.save(sub);

        alertService.raise("SUBSCRIPTION_PAYMENT",
                "Subscription payment from " + phone + " — " + sub.getPlanName() + " ₹"
                        + (sub.getUpfrontAmount() != null ? sub.getUpfrontAmount().stripTrailingZeros().toPlainString() : "?")
                        + ". Verify and activate on the Subscriptions page.",
                null, phone);

        whatsAppClient.sendText(phone,
                "We received your payment screenshot — thank you! 🙏\n\n" +
                "We'll verify it and activate your subscription shortly. You'll get a confirmation with your first delivery day. 🥖");
    }
}
