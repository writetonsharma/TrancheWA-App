package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;
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
 * Pre-confirm gate for date-first ordering. Runs after items are chosen (the
 * delivery date is already set on the draft). Re-validates capacity for the full
 * cart, then applies the multi-order rules that used to live in the date step:
 *   - same-date existing unpaid order  -> merge into it, go to ORDER_CONFIRM
 *   - other unpaid orders (other dates) -> ORDER_CONFIRM_SEPARATE warning
 *   - otherwise                         -> ADDRESS_GATE (normal path)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreConfirmMultiOrderAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final DeliveryRules deliveryRules;
    private final WhatsAppClient whatsAppClient;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");

    @Value("${bakery.order.per-order-item-limit:3}")
    private int perOrderItemLimit;

    @Override
    public String getName() { return "PRE_CONFIRM_MULTIORDER"; }

    @Override
    @Transactional
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) { ctx.setRedirectState("ADDRESS_GATE"); return; }

        Order draft = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (draft == null || draft.getStatus() != OrderStatus.DRAFT) {
            ctx.setRedirectState("ADDRESS_GATE");
            return;
        }

        LocalDate date = draft.getDeliveryDate();
        if (date == null) {
            ctx.setRedirectState("ORDER_SELECT_DATE");
            return;
        }

        Long customerId = ctx.getCustomer().getId();

        // Capacity may have changed (or the cart grew) since the date was picked.
        DeliveryRules.CartFlags flags = deliveryRules.flagsForOrder(draft.getId());
        if (!deliveryRules.isValidDeliveryDate(date, flags)) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "Sorry, *" + date.format(DATE_FMT) + "* can no longer take this order in full - "
                    + "it's reached our baking limit. Please choose another morning below.");
            ctx.setRedirectState("ORDER_SELECT_DATE");
            log.info("Draft {} no longer fits date {} at pre-confirm - re-prompting date", draft.getId(), date);
            return;
        }

        // Case 1: same-date unpaid order exists -> merge into it.
        Order existing = orderRepository
                .findTopByCustomerIdAndStatusAndDeliveryDate(customerId, OrderStatus.PENDING_CONFIRMATION, date)
                .orElse(null);
        if (existing != null) {
            // Merging must not push the day's order past the per-order item cap.
            int mergedTotal = orderService.committedItemCountForDate(customerId, draft.getId(), date);
            if (mergedTotal > perOrderItemLimit) {
                whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                        "Adding these to your cart for *" + date.format(DATE_FMT)
                        + "* would take it past its " + perOrderItemLimit
                        + "-item capacity. Please choose a different delivery day below.");
                ctx.setRedirectState("ORDER_SELECT_DATE");
                log.info("Blocked merge of draft {} into {} - would exceed item cap {} (customer {})",
                        draft.getId(), existing.getId(), perOrderItemLimit, ctx.getCustomer().getPhone());
                return;
            }
            orderService.mergeItems(draft, existing);
            orderService.cancel(draft);
            ctx.getConversation().getContext().put("orderId", existing.getId().toString());
            ctx.setRedirectState("ORDER_CONFIRM");
            log.info("Merged draft {} into existing order {} for {} (customer {})",
                    draft.getId(), existing.getId(), date, ctx.getCustomer().getPhone());
            return;
        }

        // Case 2: other unpaid orders on other dates -> separate-order warning.
        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PENDING_CONFIRMATION);
        if (!pending.isEmpty()) {
            ctx.setRedirectState("ORDER_CONFIRM_SEPARATE");
            log.info("Draft {} for {} - showing separate-order warning (customer {})",
                    draft.getId(), date, ctx.getCustomer().getPhone());
            return;
        }

        // Case 3: normal single order.
        ctx.setRedirectState("ADDRESS_GATE");
    }
}
