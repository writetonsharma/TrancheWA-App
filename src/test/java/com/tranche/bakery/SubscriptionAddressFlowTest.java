package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the fix for subscribers with no saved address: they now collect it in-flow, not via a pre-order. */
class SubscriptionAddressFlowTest extends FlowScenarioBase {

    @Test
    void subscriberWithoutAddress_reachesAddressCollection_notBlocked() {
        customer.setDeliveryAddress(null);
        customer.setSubscriptionEligible(true);
        customer = customerRepository.save(customer);

        conversation.setState("SUB_CHOOSE_DAY");
        conversationRepository.save(conversation);
        reloadConversation();

        send("MONDAY");

        assertState("SUB_ADDRESS_COLLECT");
        assertThat(sentTexts).noneMatch(t -> t.contains("place a one-time order first"));
    }

    @Test
    void subscriberWithSavedAddress_confirmsIt_beforeSummary() {
        customer.setSubscriptionEligible(true);
        customer = customerRepository.save(customer);

        conversation.setState("SUB_CHOOSE_DAY");
        conversationRepository.save(conversation);
        reloadConversation();

        send("TUESDAY");

        assertState("SUB_ADDRESS_CONFIRM");
    }
}
