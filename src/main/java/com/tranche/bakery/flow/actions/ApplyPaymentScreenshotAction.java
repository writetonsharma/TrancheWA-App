package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApplyPaymentScreenshotAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final SavePaymentScreenshotAction savePaymentScreenshotAction;

    @Override
    public String getName() { return "APPLY_PAYMENT_SCREENSHOT"; }

    @Override
    public void execute(ActionContext ctx) {
        String input = ctx.getInput().trim();
        if (!input.matches("pay_\\d+")) {
            // Not a valid button press — re-show the selection
            ctx.setRedirectState("PAYMENT_ORDER_SELECT");
            return;
        }

        long orderId = Long.parseLong(input.substring("pay_".length()));
        Order order = orderRepository.findById(orderId).orElse(null);

        if (order == null
                || !order.getCustomer().getId().equals(ctx.getCustomer().getId())
                || order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            ctx.setRedirectState("PAYMENT_ORDER_SELECT");
            return;
        }

        String mediaId = ctx.contextValue("pendingScreenshotMediaId");
        if (mediaId == null) {
            log.warn("APPLY_PAYMENT_SCREENSHOT: no pendingScreenshotMediaId in context for customer {}",
                    ctx.getCustomer().getPhone());
            ctx.setRedirectState("PAYMENT_ORDER_SELECT");
            return;
        }

        savePaymentScreenshotAction.applyScreenshot(mediaId, order);

        ctx.getConversation().getContext().put("orderId", String.valueOf(orderId));
        ctx.getConversation().getContext().remove("pendingScreenshotMediaId");

        log.info("Payment screenshot applied to order {} for customer {}",
                orderId, ctx.getCustomer().getPhone());
    }
}
