package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.subscription.SubscriptionRepository;
import com.tranche.bakery.subscription.SubscriptionStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/**
 * Gate for starting a subscription: blocks a second parallel subscription, and requires a saved
 * delivery address (weekly orders reuse it — there's no address step in the subscribe flow).
 */
@Component
@RequiredArgsConstructor
public class SubGuardAction implements FlowAction {

    private final SubscriptionRepository subscriptionRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SUB_GUARD"; }

    @Override
    public void execute(ActionContext ctx) {
        Customer customer = ctx.getCustomer();
        String phone = customer.getPhone();

        boolean alreadyHasOne =
                subscriptionRepository.existsByCustomerIdAndStatus(customer.getId(), SubscriptionStatus.PENDING_PAYMENT)
                || subscriptionRepository.existsByCustomerIdAndStatus(customer.getId(), SubscriptionStatus.ACTIVE);
        if (alreadyHasOne) {
            whatsAppClient.sendText(phone,
                    "You already have a subscription in progress. Tap *Info → My Order Status* to view or manage it. 🥖");
            ctx.setRedirectState("MAIN_MENU");
            return;
        }

        if (customer.getDeliveryAddress() == null || customer.getDeliveryAddress().isBlank()) {
            whatsAppClient.sendText(phone,
                    "Before subscribing, we need your delivery address on file. Please place a one-time order first " +
                    "(we'll save your address), then you can start a weekly subscription. Send *hi* to begin. 🥖");
            ctx.setRedirectState("IDLE");
        }
        // Otherwise no redirect — SUB_START's plan list is shown.
    }
}
