package com.tranche.bakery.flow.actions;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** Positive feedback → the customer opted in to leave a Google review; send the review link. */
@Component
@RequiredArgsConstructor
public class SendReviewLinkAction implements FlowAction {

    private final WhatsAppClient whatsAppClient;

    @Value("${bakery.review-url:https://g.page/r/CReNjvjtRwvZEBM/review}")
    private String reviewUrl;

    @Override
    public String getName() { return "SEND_REVIEW_LINK"; }

    @Override
    public void execute(ActionContext ctx) {
        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "Thank you so much! 🙏 A quick Google review means the world to a small bakery like ours:\n\n"
                        + reviewUrl + "\n\n💛");
    }
}
