package com.tranche.bakery.flow.actions;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.SubscriptionCatalog;
import com.tranche.bakery.subscription.SubscriptionCatalog.ComponentConfig;
import com.tranche.bakery.subscription.SubscriptionCatalog.OptionConfig;
import com.tranche.bakery.subscription.SubscriptionCatalog.PlanConfig;

import lombok.RequiredArgsConstructor;

/** Records the item chosen for the current component; loops to the next one or on to the day step. */
@Component
@RequiredArgsConstructor
public class SubSelectItemAction implements FlowAction {

    private final SubscriptionFlowSupport support;
    private final SubscriptionCatalog catalog;

    @Override
    public String getName() { return "SUB_SELECT_ITEM"; }

    @Override
    public void execute(ActionContext ctx) {
        PlanConfig plan = support.plan(ctx);
        OptionConfig option = support.option(ctx);
        if (plan == null || option == null) {
            ctx.setRedirectState("SUB_START");
            return;
        }
        int comp = support.intVal(ctx, "subComp", 0);
        if (comp >= option.getComponents().size()) {
            ctx.setRedirectState("SUB_CHOOSE_DAY");
            return;
        }
        ComponentConfig component = option.getComponents().get(comp);
        String name = ctx.getInput() != null ? ctx.getInput().trim() : "";
        if (!catalog.chooseFrom(component.getType(), plan.getTier()).contains(name)) {
            ctx.setRedirectState("SUB_CHOOSE_ITEMS");   // invalid pick — re-prompt same component
            return;
        }

        int next = comp + 1;
        support.put(ctx, Map.of("subItem" + comp, name, "subComp", String.valueOf(next)));
        ctx.setRedirectState(next < option.getComponents().size() ? "SUB_CHOOSE_ITEMS" : "SUB_CHOOSE_DAY");
    }
}
