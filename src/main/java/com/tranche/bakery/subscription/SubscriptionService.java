package com.tranche.bakery.subscription;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderNumberGenerator;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.subscription.SubscriptionCatalog.PlanConfig;
import com.tranche.bakery.whatsapp.CustomerNotifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core subscription logic: snapshot a chosen plan, activate on payment, and generate the weekly
 * ₹0 (prepaid) orders that flow through the normal bake list and status buttons.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    // Generate a week's order this many days before its delivery date (so it lands on the bake list).
    private static final int GENERATE_LEAD_DAYS = 2;

    private final SubscriptionCatalog catalog;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPeriodRepository periodRepository;
    private final MenuItemRepository menuItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final CustomerNotifier customerNotifier;

    /** One chosen bundle line, resolved from the plan option + the customer's pick. */
    public record ChosenItem(String itemName, int quantity, String portion) {}

    public BigDecimal totalUpfront(PlanConfig plan) {
        return plan.getWeeklyPrice().add(plan.getDeliveryCharge())
                .multiply(BigDecimal.valueOf(plan.getCommitmentWeeks()));
    }

    /** Create a PENDING_PAYMENT subscription snapshotting the plan, chosen items and delivery day. */
    @Transactional
    public Subscription createPending(Customer customer, String planCode,
                                      List<ChosenItem> chosenItems, DayOfWeek deliveryDay) {
        PlanConfig plan = catalog.plan(planCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan " + planCode));

        Subscription sub = new Subscription();
        sub.setCustomer(customer);
        sub.setPlanCode(plan.getCode());
        sub.setPlanName(plan.getName());
        sub.setTier(plan.getTier());
        sub.setWeeklyPrice(plan.getWeeklyPrice());
        sub.setDeliveryCharge(plan.getDeliveryCharge());
        sub.setCommitmentWeeks(plan.getCommitmentWeeks());
        sub.setDeliveryDay(deliveryDay);
        sub.setUpfrontAmount(totalUpfront(plan));
        sub.setStatus(SubscriptionStatus.PENDING_PAYMENT);
        for (ChosenItem ci : chosenItems) {
            SubscriptionItem item = new SubscriptionItem();
            item.setItemName(ci.itemName());
            item.setQuantity(ci.quantity());
            item.setPortion(ci.portion());
            sub.addItem(item);
        }
        return subscriptionRepository.save(sub);
    }

    /** Payment verified: activate, set the 4-week window, generate any due orders, and notify. */
    @Transactional
    public void activate(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null || sub.getStatus() == SubscriptionStatus.ACTIVE) return;

        LocalDate firstDelivery = nextOccurrence(LocalDate.now().plusDays(1), sub.getDeliveryDay());
        sub.setStartDate(firstDelivery);
        sub.setEndDate(firstDelivery.plusWeeks(sub.getCommitmentWeeks() - 1L));
        sub.setStatus(SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(sub);

        customerNotifier.subscriptionConfirmed(sub.getCustomer(), sub.getPlanName(), firstDelivery);
        generateForSubscription(sub);
        log.info("Subscription {} activated for customer {} — {} weeks from {}, delivery on {}",
                sub.getId(), sub.getCustomer().getId(), sub.getCommitmentWeeks(), firstDelivery, sub.getDeliveryDay());
    }

    /** Scheduled: create the ₹0 order for any subscription week whose delivery is near. */
    @Transactional
    public void generateDueOrders() {
        for (Subscription sub : subscriptionRepository.findAllByStatus(SubscriptionStatus.ACTIVE)) {
            generateForSubscription(sub);
        }
    }

    /** Scheduled: close out subscriptions whose commitment window has fully elapsed. */
    @Transactional
    public void completeFinished() {
        LocalDate today = LocalDate.now();
        for (Subscription sub : subscriptionRepository.findAllByStatus(SubscriptionStatus.ACTIVE)) {
            if (sub.getEndDate() != null && sub.getEndDate().isBefore(today)) {
                sub.setStatus(SubscriptionStatus.COMPLETED);
                subscriptionRepository.save(sub);
                log.info("Subscription {} completed", sub.getId());
            }
        }
    }

    /** Admin action: cancel a subscription — remaining weeks won't be generated. */
    @Transactional
    public void cancel(Long subscriptionId) {
        subscriptionRepository.findById(subscriptionId).ifPresent(sub -> {
            sub.setStatus(SubscriptionStatus.CANCELLED);
            subscriptionRepository.save(sub);
            log.info("Subscription {} cancelled", subscriptionId);
        });
    }

    // Generate any not-yet-created weekly orders for this subscription that are within the lead window.
    private void generateForSubscription(Subscription sub) {
        if (sub.getStartDate() == null) return;
        LocalDate horizon = LocalDate.now().plusDays(GENERATE_LEAD_DAYS);
        for (int week = 1; week <= sub.getCommitmentWeeks(); week++) {
            LocalDate deliveryDate = sub.getStartDate().plusWeeks(week - 1L);
            if (deliveryDate.isAfter(horizon)) continue;                 // too far out yet
            if (deliveryDate.isBefore(LocalDate.now())) continue;        // never backfill a past date
            if (periodRepository.findBySubscriptionIdAndWeekNumber(sub.getId(), week).isPresent()) continue;

            Order order = createWeeklyOrder(sub, deliveryDate);

            SubscriptionPeriod period = new SubscriptionPeriod();
            period.setSubscription(sub);
            period.setWeekNumber(week);
            period.setDeliveryDate(deliveryDate);
            period.setOrderId(order.getId());
            periodRepository.save(period);

            if (week == sub.getCommitmentWeeks()) {
                customerNotifier.subscriptionRenewal(sub.getCustomer(), deliveryDate);
            }
        }
    }

    private Order createWeeklyOrder(Subscription sub, LocalDate deliveryDate) {
        Customer customer = sub.getCustomer();
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setDeliveryDate(deliveryDate);
        order.setSubscriptionId(sub.getId());
        order.setTotalAmount(BigDecimal.ZERO);
        order.setDeliveryCharge(BigDecimal.ZERO);
        order.setDeliveryAddress(customer.getDeliveryAddress());
        order.setLocationLat(customer.getLocationLat());
        order.setLocationLng(customer.getLocationLng());
        order = orderRepository.save(order);
        order.setOrderNumber(orderNumberGenerator.generate(order.getId(), order.getCreatedAt()));
        order = orderRepository.save(order);

        for (SubscriptionItem si : sub.getItems()) {
            MenuItem menuItem = menuItemRepository.findFirstByNameAndActiveTrue(si.getItemName()).orElse(null);
            if (menuItem == null) {
                log.warn("Subscription {} week order {}: menu item '{}' not found/active — skipped",
                        sub.getId(), order.getId(), si.getItemName());
                continue;
            }
            OrderItem oi = new OrderItem();
            oi.setOrder(order);
            oi.setMenuItem(menuItem);
            oi.setQuantity(si.getQuantity());
            oi.setUnitPrice(BigDecimal.ZERO);
            oi.setSubtotal(BigDecimal.ZERO);
            if ("HALF".equalsIgnoreCase(si.getPortion())) oi.setNote("½ portion");
            orderItemRepository.save(oi);
        }
        return order;
    }

    // First date on or after `from` that falls on the given day of week.
    private LocalDate nextOccurrence(LocalDate from, DayOfWeek day) {
        LocalDate d = from;
        while (d.getDayOfWeek() != day) d = d.plusDays(1);
        return d;
    }
}
