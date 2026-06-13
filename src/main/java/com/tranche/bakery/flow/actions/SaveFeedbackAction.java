package com.tranche.bakery.flow.actions;

import com.tranche.bakery.feedback.FeedbackService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SaveFeedbackAction implements FlowAction {

    private final FeedbackService feedbackService;

    @Override
    public String getName() { return "SAVE_FEEDBACK"; }

    @Override
    public void execute(ActionContext ctx) {
        feedbackService.save(ctx.getCustomer(), ctx.getInput());
    }
}
