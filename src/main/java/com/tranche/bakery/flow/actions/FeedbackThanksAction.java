package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** Closes the feedback loop when the customer declines the review or gives a positive-but-no-review reply. */
@Component
@RequiredArgsConstructor
public class FeedbackThanksAction implements FlowAction {

    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "FEEDBACK_THANKS"; }

    @Override
    public void execute(ActionContext ctx) {
        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "Thank you for your feedback! 💛 Send *hi* whenever you'd like to order again.");
    }
}
