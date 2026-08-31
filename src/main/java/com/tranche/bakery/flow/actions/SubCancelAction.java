package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.SubscriptionService;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** Cancels the pending subscription from the payment screen (before payment is verified). */
@Component
@RequiredArgsConstructor
public class SubCancelAction implements FlowAction {

    private final SubscriptionService subscriptionService;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SUB_CANCEL"; }

    @Override
    public void execute(ActionContext ctx) {
        String subIdStr = ctx.contextValue("subId");
        if (subIdStr != null) {
            subscriptionService.cancel(Long.parseLong(subIdStr));
        }
        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "No problem — your subscription request has been cancelled. Send *hi* whenever you'd like to start again. 🥖");
    }
}
