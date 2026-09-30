package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.feedback.FeedbackService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** "Could be better" path: store the written suggestion, alert the admin, and thank the customer. */
@Component
@RequiredArgsConstructor
public class SaveFeedbackDetailAction implements FlowAction {

    private final FeedbackService feedbackService;
    private final AlertService alertService;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SAVE_FEEDBACK_DETAIL"; }

    @Override
    public void execute(ActionContext ctx) {
        String detail = ctx.getInput() != null ? ctx.getInput().trim() : "";
        if (!detail.isBlank()) {
            feedbackService.save(ctx.getCustomer(), "Improvement suggestion: " + detail);
            alertService.raise("FEEDBACK",
                    "Customer suggested an improvement: \"" + detail + "\"",
                    null, ctx.getCustomer().getPhone());
        }
        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "You're right, and thank you for telling us — we'll work on this. 💛 "
                        + "Send *hi* anytime to order again.");
    }
}
