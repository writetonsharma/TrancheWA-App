package com.tranche.bakery.flow.actions;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;

import lombok.RequiredArgsConstructor;

/** Records the chosen subscription plan and moves to the bundle-option step. */
@Component
@RequiredArgsConstructor
public class SubSelectPlanAction implements FlowAction {

    private final SubscriptionFlowSupport support;

    @Override
    public String getName() { return "SUB_SELECT_PLAN"; }

    @Override
    public void execute(ActionContext ctx) {
        String code = ctx.getInput() != null ? ctx.getInput().trim() : "";
        if (support.validPlan(code)) {
            support.put(ctx, Map.of("subPlan", code, "subOption", "", "subComp", "0"));
            ctx.setRedirectState("SUB_CHOOSE_OPTION");
        } else {
            ctx.setRedirectState("SUB_START");
        }
    }
}
