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

    private void seedBookedDemand(MenuItem item, LocalDate date, int units) {
        Customer c = new Customer();
        c.setPhone("91900001" + String.format("%04d", demandSeq++));
        c.setName("Demand " + demandSeq);
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

    // Greeting nudge lists a hot item when demand is live for the coming bake days.
    @Test
    void greeting_showsLiveBatchDiscountNudge() {
        MenuItem item = cheapUnder350();
        LocalDate date = deliveryRules.upcomingDeliverableDays(3).get(0);
        seedBookedDemand(item, date, 4);

        send("hi");

        assertThat(sentTexts).anyMatch(t ->
                t.contains("Live batch discounts") && t.contains(item.getName()));
    }

    // No hot items: greeting stays clean (no nudge text).
    @Test
    void greeting_noNudgeWhenNothingHot() {
        send("hi");
        assertThat(sentTexts).noneMatch(t -> t.contains("Live batch discounts"));
    }
}
