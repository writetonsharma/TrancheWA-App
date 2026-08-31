package com.tranche.bakery.flow.actions;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.subscription.SubscriptionCatalog;
import com.tranche.bakery.subscription.SubscriptionCatalog.OptionConfig;
import com.tranche.bakery.subscription.SubscriptionCatalog.PlanConfig;
import com.tranche.bakery.subscription.SubscriptionService.ChosenItem;

import lombok.RequiredArgsConstructor;

/** Shared context navigation for the self-serve subscription flow actions. */
@Component
@RequiredArgsConstructor
public class SubscriptionFlowSupport {

    private final SubscriptionCatalog catalog;

    public PlanConfig plan(ActionContext ctx) {
        return catalog.plan(ctx.contextValue("subPlan")).orElse(null);
    }

    public boolean validPlan(String code) {
        return catalog.plan(code).map(p -> p.isActive() && "FF".equalsIgnoreCase(p.getAudience())).orElse(false);
    }

    public OptionConfig option(ActionContext ctx) {
        PlanConfig p = plan(ctx);
        if (p == null || p.getOptions() == null) return null;
        int i = intVal(ctx, "subOption", -1);
        return (i >= 0 && i < p.getOptions().size()) ? p.getOptions().get(i) : null;
    }

    /** The resolved items chosen so far, in component order. */
    public List<ChosenItem> chosenItems(ActionContext ctx) {
        OptionConfig o = option(ctx);
        List<ChosenItem> out = new ArrayList<>();
        if (o == null) return out;
        for (int i = 0; i < o.getComponents().size(); i++) {
            String name = ctx.contextValue("subItem" + i);
            if (name != null) {
                out.add(new ChosenItem(name, o.getComponents().get(i).getQty(), o.getComponents().get(i).getPortion()));
            }
        }
        return out;
    }

    public DayOfWeek day(ActionContext ctx) {
        String d = ctx.contextValue("subDay");
        try { return d != null ? DayOfWeek.valueOf(d) : null; }
        catch (IllegalArgumentException e) { return null; }
    }

    public int intVal(ActionContext ctx, String key, int def) {
        String s = ctx.contextValue(key);
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    /** Merge the given keys into the conversation context (a fresh map so JPA detects the change). */
    public void put(ActionContext ctx, Map<String, String> updates) {
        Map<String, Object> current = ctx.getConversation().getContext();
        Map<String, Object> copy = current != null ? new HashMap<>(current) : new HashMap<>();
        copy.putAll(updates);
        ctx.getConversation().setContext(copy);
    }

    /** Drop all subscription-flow keys (used when starting a fresh signup). */
    public void clear(ActionContext ctx) {
        Map<String, Object> current = ctx.getConversation().getContext();
        Map<String, Object> copy = current != null ? new HashMap<>(current) : new HashMap<>();
        copy.keySet().removeIf(k -> k.startsWith("sub"));
        ctx.getConversation().setContext(copy);
    }
}
