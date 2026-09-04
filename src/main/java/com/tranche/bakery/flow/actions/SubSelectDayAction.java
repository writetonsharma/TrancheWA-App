package com.tranche.bakery.flow.actions;

import java.time.DayOfWeek;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;

import lombok.RequiredArgsConstructor;

/** Records the chosen delivery day (any day except Wednesday) and moves to confirmation. */
@Component
@RequiredArgsConstructor
public class SubSelectDayAction implements FlowAction {

    private final SubscriptionFlowSupport support;

    @Override
    public String getName() { return "SUB_SELECT_DAY"; }

    @Override
    public void execute(ActionContext ctx) {
        String in = ctx.getInput() != null ? ctx.getInput().trim() : "";
        DayOfWeek day = parse(in);
        if (day == null || day == DayOfWeek.WEDNESDAY) {
            ctx.setRedirectState("SUB_CHOOSE_DAY");
            return;
        }
        support.put(ctx, Map.of("subDay", day.name()));
        ctx.setRedirectState("SUB_ADDRESS_GATE");
    }

    private DayOfWeek parse(String in) {
        try { return DayOfWeek.valueOf(in.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
