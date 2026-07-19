package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Entry action for the delivery-date step. If the soonest day we would normally
 * offer is fully booked, sends a friendly heads-up explaining why it's missing
 * and when the earliest available morning is. Runs before the (filtered) date list.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotifyDateCapacityAction implements FlowAction {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");

    private final DeliveryRules deliveryRules;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "NOTIFY_DATE_CAPACITY"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        Long orderId = orderIdStr != null ? Long.parseLong(orderIdStr) : null;
        DeliveryRules.CartFlags flags = deliveryRules.flagsForOrder(orderId);

        LocalDate expected = deliveryRules.firstDeliverableDay(flags);
        if (deliveryRules.hasCapacity(expected, flags)) {
            return; // soonest normal day still has room — nothing to explain
        }

        LocalDate available = deliveryRules.firstAvailableDate(flags);

        // Collect ALL full days between expected and available (inclusive of expected)
        List<LocalDate> fullDays = new ArrayList<>();
        LocalDate d = expected;
        int scanned = 0;
        while (d.isBefore(available) && scanned < 60) {
            if (deliveryRules.isDeliverableDay(d, flags) && !deliveryRules.hasCapacity(d, flags)) {
                fullDays.add(d);
            }
            d = d.plusDays(1);
            scanned++;
        }

        String msg;
        if (fullDays.size() <= 1) {
            msg = "\u26A0\uFE0F Heads-up: we've reached our baking limit for *"
                    + expected.format(FMT) + "*, so it's fully booked.\n\n"
                    + "The earliest morning we can deliver is *" + available.format(FMT) + "*. "
                    + "Please choose from the available dates below \uD83D\uDDD3\uFE0F";
        } else {
            String fullList = fullDays.stream()
                    .map(day -> "*" + day.format(FMT) + "*")
                    .collect(Collectors.joining(", "));
            msg = "\u26A0\uFE0F Heads-up: we've reached our baking limit for "
                    + fullList + " — they're fully booked.\n\n"
                    + "The earliest morning we can deliver is *" + available.format(FMT) + "*. "
                    + "Please choose from the available dates below \uD83D\uDDD3\uFE0F";
        }
        whatsAppClient.sendText(ctx.getCustomer().getPhone(), msg);
        log.info("Notified capacity gap: {} full day(s), earliest available {} (customer {})",
                fullDays.size(), available, ctx.getCustomer().getPhone());
    }
}