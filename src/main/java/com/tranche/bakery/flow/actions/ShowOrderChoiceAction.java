package com.tranche.bakery.flow.actions;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.SubscriptionCatalog;
import com.tranche.bakery.subscription.SubscriptionCatalog.PlanConfig;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;

/** The Order choice for eligible customers, with a subscription pitch (savings + benefits) built from config. */
@Component
@RequiredArgsConstructor
public class ShowOrderChoiceAction implements FlowAction {

    private final SubscriptionCatalog catalog;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SHOW_ORDER_CHOICE"; }

    @Override
    public void execute(ActionContext ctx) {
        String phone = ctx.getCustomer().getPhone();

        StringBuilder sb = new StringBuilder("🥖 *One-time order, or subscribe & save?*\n\n");
        sb.append("A *Weekly Subscription* gets you:\n");
        sb.append("• A fresh bundle every week — mix a *half loaf* with rolls, a sweet roll, or *two half loaves*\n");
        sb.append("• Delivered on *your chosen day*\n");

        String freeWeek = freeWeekLine();
        if (freeWeek != null) sb.append(freeWeek);
        sb.append("• Prepaid — no weekly re-ordering\n\n");
        sb.append("Or place a quick *one-time order*. What would you like?");

        whatsAppClient.sendButtons(phone, sb.toString(), List.of(
                new WhatsAppMessage.Button("one_time", "One-time Order"),
                new WhatsAppMessage.Button("subscription", "Weekly Subscription")));
    }

    // "• Pay for N weeks, get week N+1 FREE — saving ₹X–₹Y every cycle" computed from the active FF plans.
    private String freeWeekLine() {
        List<PlanConfig> plans = catalog.activePlansForAudience("FF");
        BigDecimal minSave = null, maxSave = null;
        int freeWeeks = 0, paidWeeks = 0;
        for (PlanConfig p : plans) {
            int bonus = catalog.totalWeeks(p) - p.getCommitmentWeeks();
            if (bonus <= 0) continue;
            freeWeeks = Math.max(freeWeeks, bonus);
            paidWeeks = Math.max(paidWeeks, p.getCommitmentWeeks());
            BigDecimal save = p.getWeeklyPrice().multiply(BigDecimal.valueOf(bonus));
            minSave = (minSave == null) ? save : save.min(minSave);
            maxSave = (maxSave == null) ? save : save.max(maxSave);
        }
        if (freeWeeks == 0 || minSave == null) return null;

        String saveText = minSave.compareTo(maxSave) == 0
                ? "₹" + minSave.stripTrailingZeros().toPlainString()
                : "₹" + minSave.stripTrailingZeros().toPlainString() + "–₹" + maxSave.stripTrailingZeros().toPlainString();
        String getText = freeWeeks == 1
                ? "get week " + (paidWeeks + 1) + " free"
                : "get " + freeWeeks + " weeks free";
        return "• *Pay for " + paidWeeks + " weeks, " + getText + "* — you cover only delivery, saving *"
                + saveText + "* every cycle\n";
    }
}
