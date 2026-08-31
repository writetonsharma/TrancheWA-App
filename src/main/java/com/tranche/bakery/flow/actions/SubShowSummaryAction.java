package com.tranche.bakery.flow.actions;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.SubscriptionCatalog.ComponentConfig;
import com.tranche.bakery.subscription.SubscriptionCatalog.OptionConfig;
import com.tranche.bakery.subscription.SubscriptionCatalog.PlanConfig;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;

/** Shows the subscription summary + upfront total with a Confirm & Pay / Cancel choice. */
@Component
@RequiredArgsConstructor
public class SubShowSummaryAction implements FlowAction {

    private final SubscriptionFlowSupport support;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SUB_SHOW_SUMMARY"; }

    @Override
    public void execute(ActionContext ctx) {
        String phone = ctx.getCustomer().getPhone();
        PlanConfig plan = support.plan(ctx);
        OptionConfig option = support.option(ctx);
        DayOfWeek day = support.day(ctx);
        if (plan == null || option == null || day == null) {
            whatsAppClient.sendText(phone, "Something went wrong setting up your subscription. Send *hi* to start again.");
            ctx.setRedirectState("MAIN_MENU");
            return;
        }

        int weeks = plan.getCommitmentWeeks();
        BigDecimal upfront = plan.getWeeklyPrice().add(plan.getDeliveryCharge())
                .multiply(BigDecimal.valueOf(weeks));

        StringBuilder sb = new StringBuilder("*Confirm your subscription* 🥖\n\n");
        sb.append("*").append(plan.getName()).append("* — ₹")
                .append(plan.getWeeklyPrice().stripTrailingZeros().toPlainString()).append("/week\n\n");
        sb.append("Each week you'll get:\n");
        for (int i = 0; i < option.getComponents().size(); i++) {
            String name = ctx.contextValue("subItem" + i);
            sb.append("• ").append(portionLabel(option.getComponents().get(i)))
                    .append(" — ").append(name).append("\n");
        }
        sb.append("\nDelivered every *").append(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .append("* for *").append(weeks).append(" weeks*.\n");
        sb.append("Delivery: ₹").append(plan.getDeliveryCharge().stripTrailingZeros().toPlainString())
                .append("/week, included in the total.\n\n");
        sb.append("*Pay now: ₹").append(upfront.stripTrailingZeros().toPlainString())
                .append("* — ").append(weeks).append(" weeks of bakes + delivery, prepaid.");

        whatsAppClient.sendButtons(phone, sb.toString(), List.of(
                new WhatsAppMessage.Button("sub_confirm", "Confirm & Pay")));
    }

    private String portionLabel(ComponentConfig c) {
        return switch (c.getType()) {
            case "LOAF" -> "HALF".equalsIgnoreCase(c.getPortion()) ? "½ loaf" : "1 loaf";
            case "ROLL" -> c.getQty() + " rolls";
            case "SWEET" -> c.getQty() + " sweet roll";
            default -> c.getQty() + " item";
        };
    }
}
