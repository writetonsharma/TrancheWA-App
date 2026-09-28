package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Entry guard for the category browse. If the cart already fills whatever is left of the
 * chosen day's bake, go straight to the bulk-order handoff rather than letting the customer
 * browse and pick an item only to be turned away at add time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GuardCartLimitAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final DeliveryRules deliveryRules;

    @Override
    public String getName() { return "GUARD_CART_LIMIT"; }

    @Override
    public void execute(ActionContext ctx) {
        Order draft = orderRepository
                .findTopByCustomerIdAndStatusOrderByCreatedAtDesc(ctx.getCustomer().getId(), OrderStatus.DRAFT)
                .orElse(null);
        if (draft == null) return;

        // Drafts don't reserve capacity, so the draft's own items sit on top of the day's bookings.
        LocalDate date = draft.getDeliveryDate();
        long room = date == null
                ? deliveryRules.getDailyCapacity()
                : deliveryRules.remainingCapacity(date);
        int draftQty = orderService.currentDraftItemCount(ctx.getCustomer());
        if (draftQty >= room) {
            log.info("Cart of {} already fills the remaining capacity {} for {} (customer {}) -> bulk limit",
                    draftQty, room, date, ctx.getCustomer().getPhone());
            ctx.setRedirectState("ORDER_BULK_LIMIT");
        }
    }
}
