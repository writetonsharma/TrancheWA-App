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
        }
        // Address is collected in-flow (SUB_ADDRESS_GATE) before the summary — no pre-order required.
    }
}
