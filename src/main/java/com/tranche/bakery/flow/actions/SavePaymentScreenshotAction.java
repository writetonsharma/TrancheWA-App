package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentScreenshot;
import com.tranche.bakery.payment.PaymentScreenshotRepository;
import com.tranche.bakery.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SavePaymentScreenshotAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentScreenshotRepository screenshotRepository;

    @Override
    public String getName() { return "SAVE_PAYMENT_SCREENSHOT"; }

    @Override
    public void execute(ActionContext ctx) {
        String mediaId = ctx.getRawMessage().path("image").path("id").asText(null);
        if (mediaId == null) return;

        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                ctx.getCustomer().getId(), OrderStatus.PENDING_CONFIRMATION);

        if (pending.isEmpty()) return;

        if (pending.size() == 1) {
            // Single order — attach screenshot directly
            applyScreenshot(mediaId, pending.get(0));
            ctx.getConversation().getContext().put("orderId", pending.get(0).getId().toString());
            log.info("Payment screenshot saved for order {} customer {}",
                    pending.get(0).getId(), ctx.getCustomer().getPhone());
        } else {
            // Multiple orders — save media ID and ask customer which order they paid for
            ctx.getConversation().getContext().put("pendingScreenshotMediaId", mediaId);
            ctx.setRedirectState("PAYMENT_ORDER_SELECT");
            log.info("Multiple pending orders for {} — redirecting to order selection",
                    ctx.getCustomer().getPhone());
        }
    }

    public void applyScreenshot(String mediaId, Order order) {
        Payment payment = paymentRepository.findByOrder(order).orElseGet(() -> {
            Payment p = new Payment();
            p.setOrder(order);
            p.setAmount(order.getTotalAmount());
            return p;
        });
        payment.setStatus(PaymentStatus.SCREENSHOT_RECEIVED);
        paymentRepository.save(payment);

        PaymentScreenshot screenshot = new PaymentScreenshot();
        screenshot.setPayment(payment);
        screenshot.setWhatsappMediaId(mediaId);
        screenshotRepository.save(screenshot);

        order.setStatus(OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
        orderRepository.save(order);
    }
}
