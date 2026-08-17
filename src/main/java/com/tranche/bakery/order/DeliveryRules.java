package com.tranche.bakery.order;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Central delivery-scheduling rules shared by the date picker (presentation)
 * and the save action (enforcement).
 *
 * Rules:
 *  - No delivery on Wednesdays.
 *  - Bagels need 48h notice (18h cold fermentation) -> one extra lead day.
 *  - Focaccia and sweet rolls are weekend-only (Friday, Saturday, Sunday).
 *  - Daily capacity: once a delivery day is fully booked (default 15 items),
 *    it is no longer offered.
 */
@Component
@RequiredArgsConstructor
public class DeliveryRules {

    private final OrderItemRepository orderItemRepository;

    @Value("${bakery.order.cutoff-hour}")
    private int cutoffHour;

    @Value("${bakery.order.daily-capacity:15}")
    private int dailyCapacity;

    /** Orders in these statuses reserve capacity for their delivery date. */
    private static final Set<OrderStatus> CAPACITY_STATUSES = Set.of(
            OrderStatus.PENDING_CONFIRMATION,
            OrderStatus.PENDING_PAYMENT_SCREENSHOT,
            OrderStatus.PAYMENT_SCREENSHOT_RECEIVED,
            OrderStatus.PAYMENT_SCREENSHOT_VERIFIED,
            OrderStatus.PAYMENT_REVIEW_REQUIRED,
            OrderStatus.CONFIRMED,
            OrderStatus.IN_BAKING);

    /** Statuses that reserve a baking slot (daily capacity check). */
    public static Set<OrderStatus> capacityStatuses() {
        return CAPACITY_STATUSES;
    }

    /**
     * Confirmed (paid) demand basis for the dynamic batch discount. Only orders that
     * are actually paid/confirmed count toward reaching a band threshold, so unpaid
     * pending carts never establish a batch for other customers.
     */
    private static final Set<OrderStatus> BATCH_DEMAND_STATUSES = Set.of(
            OrderStatus.CONFIRMED,
            OrderStatus.IN_BAKING);

    /** Confirmed/paid statuses that count toward batch-discount demand. */
    public static Set<OrderStatus> batchDemandStatuses() {
        return BATCH_DEMAND_STATUSES;
    }

    public record CartFlags(boolean hasBagel, boolean hasWeekendOnly, int itemCount, boolean friendsAndFamily) {}

    /** Focaccia and the sweet rolls (cinnamon, babka, cardamom) deliver on weekends only. */
    private static boolean isWeekendOnly(String lowerName) {
        return lowerName.contains("focaccia")
                || lowerName.contains("cinnamon")
                || lowerName.contains("babka")
                || lowerName.contains("cardamom");
    }

    /** Inspect an order's items to determine flags and total quantity. */
    public CartFlags flagsForOrder(Long orderId, boolean friendsAndFamily) {
        boolean hasBagel = false;
        boolean hasWeekendOnly = false;
        int itemCount = 0;
        if (orderId != null) {
            for (OrderItem item : orderItemRepository.findAllByOrderId(orderId)) {
                String name = item.getMenuItem().getName().toLowerCase();
                if (name.contains("bagel")) hasBagel = true;
                if (isWeekendOnly(name)) hasWeekendOnly = true;
                itemCount += item.getQuantity();
            }
        }
        return new CartFlags(hasBagel, hasWeekendOnly, itemCount, friendsAndFamily);
    }

    /** The next {@code count} deliverable days (ignoring cart constraints), for nudges. */
    public List<LocalDate> upcomingDeliverableDays(int count) {
        CartFlags empty = new CartFlags(false, false, 0, false);
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = earliestDate(empty);
        int scanned = 0;
        while (days.size() < count && scanned < 60) {
            if (isDeliverableDay(d, empty)) days.add(d);
            d = d.plusDays(1);
            scanned++;
        }
        return days;
    }

    public int getDailyCapacity() {
        return dailyCapacity;
    }

    /** Items already reserved for a delivery date across active orders. */
    public long bookedQuantity(LocalDate date) {
        return orderItemRepository.sumBookedQuantity(date, CAPACITY_STATUSES);
    }

    /** Remaining capacity for a delivery date (never negative). */
    public long remainingCapacity(LocalDate date) {
        return Math.max(0, dailyCapacity - bookedQuantity(date));
    }

    /** Whether a date can still fit this cart (at least 1 item if the cart is empty). */
    public boolean hasCapacity(LocalDate date, CartFlags flags) {
        int needed = Math.max(1, flags.itemCount());
        return remainingCapacity(date) >= needed;
    }

    /** Earliest deliverable date given the cutoff and the 48h bagel lead time. */
    public LocalDate earliestDate(CartFlags flags) {
        int baseLead = LocalTime.now().getHour() >= cutoffHour ? 2 : 1;
        int lead = baseLead + (flags.hasBagel() ? 1 : 0);
        return LocalDate.now().plusDays(lead);
    }

    /** Day-of-week eligibility (Wednesday closed; focaccia & sweet rolls weekend-only). */
    public boolean isDeliverableDay(LocalDate date, CartFlags flags) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.WEDNESDAY) {
            return false;
        }
        // Friends & Family may take weekend-only items on any (non-Wednesday) delivery day.
        if (flags.hasWeekendOnly() && !flags.friendsAndFamily()
                && dow != DayOfWeek.FRIDAY
                && dow != DayOfWeek.SATURDAY
                && dow != DayOfWeek.SUNDAY) {
            return false;
        }
        return true;
    }

    /** A date the customer may actually select: right day type AND has room. */
    public boolean isAvailable(LocalDate date, CartFlags flags) {
        return isDeliverableDay(date, flags) && hasCapacity(date, flags);
    }

    /** Full validity: not before the earliest date AND available. */
    public boolean isValidDeliveryDate(LocalDate date, CartFlags flags) {
        return !date.isBefore(earliestDate(flags)) && isAvailable(date, flags);
    }

    /**
     * Whether a single menu item can be delivered on the given date, applying the
     * same day-type and lead-time rules used for a whole cart. Used by the
     * date-first menu to hide items that do not fit the customer chosen morning
     * (e.g. focaccia on a weekday, or a bagel that cannot clear its 48h ferment).
     */
    public boolean itemDeliverableOn(String itemName, LocalDate date, boolean friendsAndFamily) {
        String n = itemName == null ? "" : itemName.toLowerCase();
        CartFlags flags = new CartFlags(n.contains("bagel"), isWeekendOnly(n), 1, friendsAndFamily);
        return isDeliverableDay(date, flags) && !date.isBefore(earliestDate(flags));
    }

    /** The soonest day we would normally offer (day-type valid), ignoring capacity. */
    public LocalDate firstDeliverableDay(CartFlags flags) {
        LocalDate d = earliestDate(flags);
        int scanned = 0;
        while (!isDeliverableDay(d, flags) && scanned < 60) {
            d = d.plusDays(1);
            scanned++;
        }
        return d;
    }

    /** The soonest day the customer can actually book (day-type valid AND has room). */
    public LocalDate firstAvailableDate(CartFlags flags) {
        LocalDate d = earliestDate(flags);
        int scanned = 0;
        while (!isAvailable(d, flags) && scanned < 60) {
            d = d.plusDays(1);
            scanned++;
        }
        return d;
    }
}