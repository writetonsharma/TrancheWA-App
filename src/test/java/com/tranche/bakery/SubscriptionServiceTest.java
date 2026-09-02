package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionPeriodRepository;
import com.tranche.bakery.subscription.SubscriptionRepository;
import com.tranche.bakery.subscription.SubscriptionService;
import com.tranche.bakery.subscription.SubscriptionService.ChosenItem;
import com.tranche.bakery.subscription.SubscriptionStatus;

class SubscriptionServiceTest extends FlowScenarioBase {

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionPeriodRepository periodRepository;
    @Autowired private OrderRepository orders;

    // A delivery day 1–2 days out (never Wednesday) so activation generates the first order immediately.
    private DayOfWeek soonDeliveryDay() {
        LocalDate d = LocalDate.now().plusDays(1);
        if (d.getDayOfWeek() == DayOfWeek.WEDNESDAY) d = d.plusDays(1);
        return d.getDayOfWeek();
    }

    private List<ChosenItem> halfLoafPlusRolls() {
        return List.of(new ChosenItem("Classic Table White", 1, "HALF"),
                new ChosenItem("Whole Wheat Rolls", 4, "FULL"));
    }

    @Test
    void createPending_snapshotsPlanItemsAndUpfront() {
        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL", halfLoafPlusRolls(), DayOfWeek.MONDAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PENDING_PAYMENT);
        assertThat(sub.getWeeklyPrice()).isEqualByComparingTo("200");
        assertThat(sub.getDeliveryCharge()).isEqualByComparingTo("50");   // inherited app-wide charge (test profile)
        assertThat(sub.getUpfrontAmount()).isEqualByComparingTo("1050");  // (200 × 4 bakes) + (50 × 5 deliveries); 5th week's bread free
        assertThat(sub.getBonusWeeks()).isEqualTo(1);
        assertThat(sub.getItems()).hasSize(2);
        assertThat(sub.getItems().get(0).getPortion()).isEqualTo("HALF");
    }

    @Test
    void regularValueAndSavings_valueBundleByListPrice() {
        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF"),
                        new ChosenItem("Whole Wheat Rolls", 4, "FULL")), DayOfWeek.MONDAY);

        // ½ × ₹260 loaf + 4/6 × ₹260 rolls = 130 + 173.33
        assertThat(sub.getRegularValue()).isEqualByComparingTo("303.33");
        // regular 303.33 × 5 weeks − ₹200 × 4 paid weeks
        assertThat(subscriptionService.savingsFor(sub)).isEqualByComparingTo("717");
    }

    @Test
    void activate_setsWindowAndGeneratesFirstZeroOrder() {
        DayOfWeek day = soonDeliveryDay();
        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL", halfLoafPlusRolls(), day);

        subscriptionService.activate(sub.getId());

        Subscription reloaded = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getStartDate().getDayOfWeek()).isEqualTo(day);
        assertThat(reloaded.getEndDate()).isEqualTo(reloaded.getStartDate().plusWeeks(4)); // 4 paid + 1 free

        var periods = periodRepository.findAllBySubscriptionId(sub.getId());
        assertThat(periods).hasSize(1);
        Order order = orders.findById(periods.get(0).getOrderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getSubscriptionId()).isEqualTo(sub.getId());
        assertThat(order.getTotalAmount()).isEqualByComparingTo("0");
        assertThat(order.getDeliveryDate()).isEqualTo(reloaded.getStartDate());
    }

    @Test
    void generateDueOrders_isIdempotent() {
        DayOfWeek day = soonDeliveryDay();
        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF")), day);
        subscriptionService.activate(sub.getId());

        subscriptionService.generateDueOrders();
        subscriptionService.generateDueOrders();

        assertThat(periodRepository.findAllBySubscriptionId(sub.getId())).hasSize(1);
    }

    @Test
    void completeFinished_marksCompletedAfterEndDate() {
        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF")), soonDeliveryDay());
        subscriptionService.activate(sub.getId());
        jdbcTemplate.update("UPDATE subscriptions SET end_date = ? WHERE id = ?",
                java.sql.Date.valueOf(LocalDate.now().minusDays(1)), sub.getId());

        subscriptionService.completeFinished();

        assertThat(subscriptionRepository.findById(sub.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.COMPLETED);
    }

    @Test
    void cancelForCustomer_onlyOwnCancellableSubscription() {
        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF")), DayOfWeek.MONDAY);

        assertThat(subscriptionService.cancelForCustomer(sub.getId(), 999_999L)).isFalse();
        assertThat(subscriptionService.cancelForCustomer(sub.getId(), customer.getId())).isTrue();
        assertThat(subscriptionRepository.findById(sub.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void reorderLookup_ignoresSubscriptionOrders() {
        // A real one-time order, then an activated subscription whose ₹0 weekly order is more recent.
        Order realOrder = new Order();
        realOrder.setCustomer(customer);
        realOrder.setStatus(OrderStatus.COMPLETED);
        orders.save(realOrder);

        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF"),
                        new ChosenItem("Whole Wheat Rolls", 4, "FULL")), soonDeliveryDay());
        subscriptionService.activate(sub.getId());

        Order last = orders.findTopByCustomerIdAndSubscriptionIdIsNullAndStatusInOrderByCreatedAtDesc(
                customer.getId(),
                List.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.COMPLETED))
                .orElseThrow();

        assertThat(last.getSubscriptionId()).as("reorder must skip ₹0 subscription orders").isNull();
        assertThat(last.getId()).isEqualTo(realOrder.getId());
    }

    @Test
    void cancel_alsoCancelsUpcomingWeeklyOrder() {
        DayOfWeek day = soonDeliveryDay();
        Subscription sub = subscriptionService.createPending(customer, "FF_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF")), day);
        subscriptionService.activate(sub.getId());

        Long orderId = periodRepository.findAllBySubscriptionId(sub.getId()).get(0).getOrderId();
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        subscriptionService.cancel(sub.getId());

        assertThat(subscriptionRepository.findById(sub.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .as("the generated weekly order is cancelled too")
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void status_showsPendingSubscription() {
        customer.setSubscriptionEligible(true);
        customerRepository.save(customer);
        subscriptionService.createPending(customer, "FF_NORMAL",
                List.of(new ChosenItem("Classic Table White", 1, "HALF")), DayOfWeek.MONDAY);

        send("hi");
        send("info");
        send("order_status");

        assertThat(sentButtonBodies).anyMatch(b -> b.contains("awaiting payment"));
    }
}
