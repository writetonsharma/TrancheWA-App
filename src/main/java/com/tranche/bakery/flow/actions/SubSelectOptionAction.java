package com.tranche.bakery.flow.actions;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.SubscriptionCatalog.PlanConfig;

import lombok.RequiredArgsConstructor;

/** Records the chosen bundle option and starts the per-component item loop. */
@Component
@RequiredArgsConstructor
public class SubSelectOptionAction implements FlowAction {

    private final SubscriptionFlowSupport support;

    @Override
    public String getName() { return "SUB_SELECT_OPTION"; }

    @Override
    public void execute(ActionContext ctx) {
        PlanConfig plan = support.plan(ctx);
        String in = ctx.getInput() != null ? ctx.getInput().trim() : "";
        Integer idx = parseOptionIndex(in);
        if (plan == null || idx == null || idx >= plan.getOptions().size()) {
            ctx.setRedirectState("SUB_CHOOSE_OPTION");
            return;
        }
        support.put(ctx, Map.of("subOption", idx.toString(), "subComp", "0"));
        ctx.setRedirectState("SUB_CHOOSE_ITEMS");
    }

    private Integer parseOptionIndex(String in) {
        if (in == null || !in.startsWith("opt")) return null;
        try { return Integer.parseInt(in.substring(3)); } catch (NumberFormatException e) { return null; }
    }
}
