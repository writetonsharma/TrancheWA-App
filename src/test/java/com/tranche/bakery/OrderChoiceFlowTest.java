package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The Order button offers the one-time vs subscription choice to every customer. */
class OrderChoiceFlowTest extends FlowScenarioBase {

    @Test
    void eligibleCustomer_seesSubscriptionChoice() {
        customer.setSubscriptionEligible(true);
        customerRepository.save(customer);

        send("hi");
        send("order");

        assertThat(sentButtonTitles).contains("One-time Order", "Weekly Subscription");
    }

    @Test
    void publicCustomer_alsoSeesSubscriptionChoice() {
        send("hi");
        sentButtonTitles.clear();
        send("order");

        assertThat(sentButtonTitles).contains("One-time Order", "Weekly Subscription");
    }
}
