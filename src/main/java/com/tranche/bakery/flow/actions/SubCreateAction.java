package com.tranche.bakery.flow.actions;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionService;
import com.tranche.bakery.subscription.SubscriptionService.ChosenItem;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** Creates the PENDING_PAYMENT subscription from the chosen bundle and stores its id for the QR step. */
@Component
@RequiredArgsConstructor
public class SubCreateAction implements FlowAction {

    private final SubscriptionFlowSupport support;
    private final SubscriptionService subscriptionService;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SUB_CREATE"; }

    @Override
    public void execute(ActionContext ctx) {
        List<ChosenItem> items = support.chosenItems(ctx);
        DayOfWeek day = support.day(ctx);
        var plan = support.plan(ctx);
        if (plan == null || day == null || items.isEmpty()
                || items.size() != support.option(ctx).getComponents().size()) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "Something went wrong setting up your subscription. Send *hi* to start again.");
            ctx.setRedirectState("MAIN_MENU");
            return;
        }
        Subscription sub = subscriptionService.createPending(ctx.getCustomer(), plan.getCode(), items, day);
        support.put(ctx, Map.of("subId", sub.getId().toString()));
    }
}
