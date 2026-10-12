package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionRepository;
import com.tranche.bakery.subscription.SubscriptionService;
import com.tranche.bakery.subscription.SubscriptionService.ChosenItem;
import com.tranche.bakery.subscription.SubscriptionStatus;

/** End-of-subscription renewal: Renew same → payment, Change plan → picker. */
class RenewalFlowTest extends FlowScenarioBase {

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;

    private DayOfWeek soonDay() {
        LocalDate d = LocalDate.now().plusDays(1);
        if (d.getDayOfWeek() == DayOfWeek.WEDNESDAY) d = d.plusDays(1);
        return d.getDayOfWeek();
    }

    private void completedSubscription() {
        Subscription s = subscriptionService.createPending(customer, "PUB_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF"),
                        new ChosenItem("100% Whole Wheat Rolls", 4, "FULL")), soonDay());
        subscriptionService.activate(s.getId());
        jdbcTemplate.update("UPDATE subscriptions SET status = 'COMPLETED' WHERE id = ?", s.getId());
    }

    @Test
    void renewSame_clonesLastSubscriptionAndGoesToPayment() {
        completedSubscription();

        send("hi");
        send("renew_yes");

        assertState("SUB_PAYMENT");
        assertThat(subscriptionRepository.findAllByCustomerIdOrderByCreatedAtDesc(customer.getId()))
                .anyMatch(s -> s.getStatus() == SubscriptionStatus.PENDING_PAYMENT
                        && "PUB_NORMAL".equals(s.getPlanCode()));
    }

    @Test
    void changePlan_opensThePlanPicker() {
        completedSubscription();

        send("hi");
        send("renew_change");

        assertState("SUB_START");
    }

    @Test
    void renewSame_withNoPriorSubscription_startsFresh() {
        send("hi");
        send("renew_yes");

        assertState("SUB_START");
    }
}
