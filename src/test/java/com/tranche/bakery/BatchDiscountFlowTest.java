package com.tranche.bakery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;

/**
 * Scenarios for the dynamic batch discount. Seed bands come from V22:
 *   under 350 -> 4 units -> +5%, 350..600 -> 2 -> +4%, 600+ -> 1 -> +4%.
 * The extra percent is taken off the already-discounted line price and only once
 * the item's booked demand for the chosen delivery date reaches the band threshold.
 */
class BatchDiscountFlowTest extends FlowScenarioBase {

    @Autowired private OrderService orderService;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private DeliveryRules deliveryRules;

    private static int demandSeq = 0;

    private MenuItem cheapUnder350() {
        return itemRepository.findAll().stream()
                .filter(MenuItem::isActive)
                .filter(i -> i.getPrice().compareTo(new BigDecimal("350")) < 0)
                .filter(i -> {
                    String n = i.getName().toLowerCase();
                    return !n.contains("bagel") && !n.contains("focaccia");
                })
                .min(Comparator.comparing(MenuItem::getPrice))
                .orElseThrow();
    }

    // A plain item in the 350..600 band (batch threshold 2, +4%), no lead-time quirks.
    private MenuItem midBand350to600() {
        return itemRepository.findAll().stream()
                .filter(MenuItem::isActive)
                .filter(i -> i.getPrice().compareTo(new BigDecimal("350")) >= 0
                        && i.getPrice().compareTo(new BigDecimal("600")) < 0)
                .filter(i -> {
                    String n = i.getName().toLowerCase();
                    return !n.contains("bagel") && !n.contains("focaccia");
                })
                .min(Comparator.comparing(MenuItem::getPrice))
                .orElseThrow();
    }

    private void seedBookedDemand(MenuItem item, LocalDate date, int units) {
        Customer c = new Customer();
        c.setPhone("91900001" + String.format("%04d", demandSeq++));
        c.setName("Demand " + demandSeq);
        c = customerRepository.save(c);

        Order o = new Order();
        o.setCustomer(c);
        o.setStatus(OrderStatus.CONFIRMED);
        o.setDeliveryDate(date);
        o = orderRepository.save(o);

        OrderItem oi = new OrderItem();
        oi.setOrder(o);
        oi.setMenuItem(item);
        oi.setQuantity(units);
        oi.setUnitPrice(item.getPrice());
        oi.setSubtotal(item.getPrice().multiply(BigDecimal.valueOf(units)));
        orderItemRepository.save(oi);
    }

    private Order draftFor(MenuItem item, LocalDate date, int qty) {
        Order draft = orderService.getOrCreateDraft(customer, conversation);
        orderService.addItem(draft, item.getId(), qty);
        draft.setDeliveryDate(date);
        orderRepository.save(draft);
        orderService.recalculate(draft);
        return orderRepository.findById(draft.getId()).orElseThrow();
    }

    // At/over the band threshold: the extra discount applies, stacked on the launch 20%.
    @Test
    void batchDiscount_appliesAtThreshold() {
        MenuItem item = cheapUnder350();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);
        seedBookedDemand(item, date, 4); // band1 threshold is 4 units

        Order order = draftFor(item, date, 1);

        assertThat(order.getBatchDiscountAmount()).isGreaterThan(BigDecimal.ZERO);
        // Total invariant: list - launchDiscount - batchDiscount + delivery
        BigDecimal listSubtotal = item.getPrice();
        assertThat(order.getTotalAmount()).isEqualByComparingTo(
                listSubtotal.subtract(order.getDiscountAmount())
                        .subtract(order.getBatchDiscountAmount())
                        .add(order.getDeliveryCharge()));
    }

    // Just below the threshold: no batch discount yet.
    @Test
    void batchDiscount_notAppliedBelowThreshold() {
        MenuItem item = cheapUnder350();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);
        seedBookedDemand(item, date, 3); // one short of the 4-unit trigger

        Order order = draftFor(item, date, 1);

        assertThat(order.getBatchDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getBatchDiscountLabel()).isNull();
    }

    // No delivery date yet (cart stage): batch discount cannot be evaluated.
    @Test
    void batchDiscount_notAppliedWithoutDate() {
        MenuItem item = cheapUnder350();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);
        seedBookedDemand(item, date, 4);

        Order draft = orderService.getOrCreateDraft(customer, conversation);
        orderService.addItem(draft, item.getId(), 1); // no date set
        draft = orderRepository.findById(draft.getId()).orElseThrow();

        assertThat(draft.getBatchDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Batch discount nudge is shown after date selection when demand is live for that day.
    @Test
    void dateSelection_showsBatchDiscountNudge() {
        MenuItem item = midBand350to600(); // threshold 2, fits within test capacity (3)
        LocalDate date = deliveryRules.upcomingDeliverableDays(3).get(0);
        seedBookedDemand(item, date, 2);

        send("hi");
        send("order");
        send(date.toString());

        assertThat(sentTexts).anyMatch(t ->
                t.contains("Batch discount active for this day") && t.contains(item.getName()));
    }

    // No hot items for the chosen date: no nudge shown.
    @Test
    void dateSelection_noNudgeWhenNothingHot() {
        send("hi");
        send("order");
        send(nextDeliveryDate());
        assertThat(sentTexts).noneMatch(t -> t.contains("Batch discount active"));
    }

    // Nudge is NOT shown at main menu greeting (moved to post-date-selection).
    @Test
    void greeting_noNudgeAtMainMenu() {
        MenuItem item = midBand350to600();
        LocalDate date = deliveryRules.upcomingDeliverableDays(3).get(0);
        seedBookedDemand(item, date, 2);

        send("hi");

        assertThat(sentTexts).noneMatch(t -> t.contains("batch discount") || t.contains("Batch discount"));
    }

    // Batch discount nudge is specific to the chosen day: demand on a different day
    // does NOT trigger the nudge for the selected date.
    @Test
    void dateSelection_nudgeSpecificToChosenDay() {
        MenuItem item = midBand350to600();
        java.util.List<LocalDate> upcoming = deliveryRules.upcomingDeliverableDays(3);
        LocalDate dateWithDemand = upcoming.get(0);
        LocalDate dateWithout = upcoming.size() > 1 ? upcoming.get(1) : upcoming.get(0).plusDays(2);
        // Skip if both resolve to the same date
        if (dateWithDemand.equals(dateWithout)) return;

        seedBookedDemand(item, dateWithDemand, 2);

        send("hi");
        send("order");
        send(dateWithout.toString());

        assertThat(sentTexts).noneMatch(t -> t.contains("Batch discount active"));
    }

    // At exactly the threshold, all units establish the batch -> no discount.
    // Mid band threshold is 2, so an order of 2 with no other demand gets nothing.
    @Test
    void batchDiscount_atThresholdNotDiscounted() {
        MenuItem item = midBand350to600();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);

        Order establishing = draftFor(item, date, 2);

        assertThat(establishing.getBatchDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(establishing.getBatchDiscountLabel()).isNull();
    }

    // Only surplus units (beyond the threshold) are discounted. Mid band threshold is
    // 2, so an order of 3 with no other demand discounts exactly 1 unit.
    @Test
    void batchDiscount_onlyAppliesToSurplusUnits() {
        MenuItem item = midBand350to600();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);

        Order surplus = draftFor(item, date, 3);

        assertThat(surplus.getBatchDiscountAmount()).isGreaterThan(BigDecimal.ZERO);

        // The discount equals exactly one unit's worth (2 of the 3 establish the batch).
        BigDecimal perUnitListPrice = item.getPrice();
        BigDecimal discountedPerUnit = perUnitListPrice
                .subtract(surplus.getDiscountAmount().divide(BigDecimal.valueOf(3), 10, java.math.RoundingMode.HALF_UP));
        BigDecimal expectedOneUnit = discountedPerUnit
                .multiply(new BigDecimal("0.04"))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        assertThat(surplus.getBatchDiscountAmount()).isEqualByComparingTo(expectedOneUnit);
    }

    // Confirmed-only basis: an unpaid PENDING order from another customer does NOT
    // count toward the threshold, so it cannot establish a batch for this customer.
    @Test
    void batchDiscount_pendingDemandDoesNotCount() {
        MenuItem item = midBand350to600();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);
        seedPendingDemand(item, date, 5); // well over threshold, but unpaid

        Order order = draftFor(item, date, 1);

        assertThat(order.getBatchDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getBatchDiscountLabel()).isNull();
    }

    // Confirmed demand from OTHERS already meets the threshold, so a new customer
    // joining the batch gets the discount on all of their units.
    @Test
    void batchDiscount_joiningEstablishedBatchDiscountsAllUnits() {
        MenuItem item = midBand350to600();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);
        seedBookedDemand(item, date, 2); // CONFIRMED, meets threshold 2

        Order order = draftFor(item, date, 1);

        assertThat(order.getBatchDiscountAmount()).isGreaterThan(BigDecimal.ZERO);
    }

    // Mixed demand: 1 unit already CONFIRMED by others (threshold 2) means this order's
    // first unit finishes establishing the batch and only its second unit is discounted.
    @Test
    void batchDiscount_mixedDemandDiscountsOnlyBeyondThreshold() {
        MenuItem item = midBand350to600();
        LocalDate date = deliveryRules.upcomingDeliverableDays(1).get(0);
        seedBookedDemand(item, date, 1); // CONFIRMED by another customer

        Order order = draftFor(item, date, 2); // one unit establishes, one is surplus

        assertThat(order.getBatchDiscountAmount()).isGreaterThan(BigDecimal.ZERO);

        // Exactly one unit's worth of discount (not two).
        BigDecimal discountedPerUnit = item.getPrice()
                .subtract(order.getDiscountAmount().divide(BigDecimal.valueOf(2), 10, java.math.RoundingMode.HALF_UP));
        BigDecimal expectedOneUnit = discountedPerUnit
                .multiply(new BigDecimal("0.04"))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        assertThat(order.getBatchDiscountAmount()).isEqualByComparingTo(expectedOneUnit);
    }

    private void seedPendingDemand(MenuItem item, LocalDate date, int units) {
        Customer c = new Customer();
        c.setPhone("91900002" + String.format("%04d", demandSeq++));
        c.setName("Pending " + demandSeq);
        c = customerRepository.save(c);

        Order o = new Order();
        o.setCustomer(c);
        o.setStatus(OrderStatus.PENDING_CONFIRMATION);
        o.setDeliveryDate(date);
        o = orderRepository.save(o);

        OrderItem oi = new OrderItem();
        oi.setOrder(o);
        oi.setMenuItem(item);
        oi.setQuantity(units);
        oi.setUnitPrice(item.getPrice());
        oi.setSubtotal(item.getPrice().multiply(BigDecimal.valueOf(units)));
        orderItemRepository.save(oi);
    }
}
