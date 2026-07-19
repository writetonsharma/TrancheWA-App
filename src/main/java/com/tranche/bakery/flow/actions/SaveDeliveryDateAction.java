package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Date-first flow: the customer chooses the delivery day BEFORE browsing the menu.
 * This action validates the chosen date, enforces the max-pending-orders cap, then
 * creates (or reuses) the draft and stamps the date on it so subsequent item adds
 * recalculate live batch discounts for that day.
 *
 * For a brand-new (empty) order it hands off to the category browse; for a reorder
 * (draft already carries items) it hands off to the pre-confirm gate, which runs
 * the same-date merge / separate-order logic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SaveDeliveryDateAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final WhatsAppClient whatsAppClient;
    private final DeliveryRules deliveryRules;

    static final int MAX_PENDING_ORDERS = 3;

    @Value("${bakery.order.per-order-item-limit:3}")
    private int perOrderItemLimit;

    @Override
    public String getName() { return "SAVE_DELIVERY_DATE"; }

    @Override
    @Transactional
    public void execute(ActionContext ctx) {
        String dateStr = ctx.contextValue("deliveryDate");
        if (dateStr == null) return;

        LocalDate deliveryDate;
        try {
            deliveryDate = LocalDate.parse(dateStr);
        } catch (Exception e) {
            return;
        }

        Long customerId = ctx.getCustomer().getId();
        String phone = ctx.getCustomer().getPhone();

        // Reuse the current draft if one exists (reorder), else create a fresh one.
        Order draft = orderService.getOrCreateDraft(ctx.getCustomer(), ctx.getConversation());
        ctx.getConversation().getContext().put("orderId", draft.getId().toString());

        // Per-date item cap: any same-date unpaid order will merge into this cart, so if
        // it is already at the item limit no more can be added for that day. Stop here
        // and let the customer pay for it or pick a different delivery day.
        int alreadyBooked = orderService.pendingItemCountForDate(customerId, deliveryDate);
        if (alreadyBooked >= perOrderItemLimit) {
            whatsAppClient.sendText(phone,
                    "Your cart for *" + deliveryDate.format(DATE_FMT) + "* is already full - it holds up to "
                    + perOrderItemLimit + " items. "
                    + "You can pay for that order, or pick a different delivery day below.\n\n"
                    + "To pay, send *hi*, then tap *Info* \u2192 *My Order Status* and choose the order to pay.");
            ctx.setRedirectState("ORDER_SELECT_DATE");
            log.info("Blocked date {} for {} - same-date order already at item cap {}",
                    deliveryDate, phone, perOrderItemLimit);
            return;
        }

        // Validate against this cart's constraints (empty cart => generic rules).
        DeliveryRules.CartFlags flags = deliveryRules.flagsForOrder(draft.getId());
        if (!deliveryRules.isValidDeliveryDate(deliveryDate, flags)) {
            boolean dayTypeOk = deliveryRules.isDeliverableDay(deliveryDate, flags)
                    && !deliveryDate.isBefore(deliveryRules.earliestDate(flags));
            String reason = dayTypeOk
                    ? fullDateMessage(deliveryDate, deliveryRules.firstAvailableDate(flags))
                    : invalidDateMessage(flags);
            whatsAppClient.sendText(phone, reason);
            ctx.setRedirectState("ORDER_SELECT_DATE");
            log.info("Rejected delivery date {} for draft {} - re-prompting", deliveryDate, draft.getId());
            return;
        }

        // Max concurrent unpaid orders. A same-date existing order is exempt because
        // it will merge (not add) at the pre-confirm step.
        boolean sameDateExists = orderRepository
                .findTopByCustomerIdAndStatusAndDeliveryDate(customerId, OrderStatus.PENDING_CONFIRMATION, deliveryDate)
                .isPresent();
        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PENDING_CONFIRMATION);
        if (!sameDateExists && pending.size() >= MAX_PENDING_ORDERS) {
            orderService.cancel(draft);
            ctx.getConversation().getContext().remove("orderId");
            whatsAppClient.sendText(phone,
                    "You already have " + MAX_PENDING_ORDERS + " orders awaiting payment. " +
                    "Please complete payment for an existing order before placing a new one.\n\n" +
                    "To pay, send *hi*, then tap *Info* \u2192 *My Order Status* and choose the order to pay.");
            ctx.setRedirectState("IDLE");
            log.info("Blocked new order for {} - max {} pending orders reached", phone, MAX_PENDING_ORDERS);
            return;
        }

        // Stamp the date now so item adds recalculate live batch discounts for the day.
        draft.setDeliveryDate(deliveryDate);
        orderRepository.save(draft);
        orderService.recalculate(draft);

        // Reorder: draft already has items -> straight to the pre-confirm gate.
        if (flags.itemCount() > 0) {
            ctx.setRedirectState("PRE_CONFIRM_GATE");
            log.info("Delivery date {} saved for reorder draft {} - to pre-confirm", deliveryDate, draft.getId());
            return;
        }

        // New order: proceed to the (date-filtered) category browse.
        log.info("Delivery date {} saved for new draft {} (customer {})", deliveryDate, draft.getId(), phone);
    }

    private String invalidDateMessage(DeliveryRules.CartFlags flags) {
        StringBuilder sb = new StringBuilder("That date isn't available for this order. ");
        if (flags.hasBagel()) {
            sb.append("Bagels need a 48-hour head start (18-hour cold fermentation). ");
        }
        if (flags.hasFocaccia()) {
            sb.append("Focaccia is baked for weekends only (Friday to Sunday). ");
        }
        sb.append("Please pick one of the dates below.");
        return sb.toString();
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");

    private String fullDateMessage(LocalDate requested, LocalDate earliestAvailable) {
        return "Sorry, *" + requested.format(DATE_FMT) + "* is fully booked - we've reached our baking limit for that day. "
                + "The earliest morning we can deliver is *" + earliestAvailable.format(DATE_FMT) + "*. "
                + "Please pick one of the dates below.";
    }
}
