package com.tranche.bakery.flow.actions;

import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recovers a payment that arrives after the 5 PM cutoff has already cancelled the customer's order.
 * The order's items and amount are preserved; the customer only picks a fresh (valid) bake day and
 * the previously-sent screenshot is carried over — they are never asked to pay again.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviveLatePaymentAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final SavePaymentScreenshotAction savePaymentScreenshotAction;

    @Override
    public String getName() { return "REVIVE_LATE_PAYMENT"; }

    @Override
    @Transactional
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        String dateStr    = ctx.contextValue("deliveryDate");
        String mediaId    = ctx.contextValue("lateScreenshotMediaId");

        if (orderIdStr == null || mediaId == null) {
            ctx.setRedirectState("IDLE");
            return;
        }

        LocalDate deliveryDate;
        try {
            deliveryDate = LocalDate.parse(dateStr);
        } catch (Exception e) {
            // Not a valid date pick — re-show the date list.
            ctx.setRedirectState("LATE_PAYMENT_DATE");
            return;
        }

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null
                || !order.getCustomer().getId().equals(ctx.getCustomer().getId())
                || order.getStatus() != OrderStatus.CANCELLED
                || !order.isCutoffCancelled()) {
            ctx.setRedirectState("IDLE");
            return;
        }

        // Revive the cancelled order on the newly-chosen bake day, then carry the payment over.
        order.setDeliveryDate(deliveryDate);
        order.setCutoffCancelled(false);
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        orderRepository.save(order);

        savePaymentScreenshotAction.applyScreenshot(mediaId, order);

        ctx.getConversation().getContext().put("orderId", order.getId().toString());
        ctx.getConversation().getContext().remove("lateScreenshotMediaId");

        log.info("Revived cutoff-cancelled order {} for customer {} on {}",
                order.getId(), ctx.getCustomer().getPhone(), deliveryDate);
    }
}
