package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentScreenshot;
import com.tranche.bakery.payment.PaymentScreenshotRepository;
import com.tranche.bakery.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) return;

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) return;

        // Get or create Payment record
        Payment payment = paymentRepository.findAll().stream()
                .filter(p -> p.getOrder().getId().equals(order.getId()))
                .findFirst()
                .orElseGet(() -> {
                    Payment p = new Payment();
                    p.setOrder(order);
                    p.setAmount(order.getTotalAmount());
                    return p;
                });
        payment.setStatus(PaymentStatus.SCREENSHOT_RECEIVED);
        paymentRepository.save(payment);

        // Extract WhatsApp media ID from raw message
        String mediaId = ctx.getRawMessage().path("image").path("id").asText(null);

        PaymentScreenshot screenshot = new PaymentScreenshot();
        screenshot.setPayment(payment);
        screenshot.setWhatsappMediaId(mediaId);
        screenshotRepository.save(screenshot);

        order.setStatus(com.tranche.bakery.order.OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
        orderRepository.save(order);

        log.info("Payment screenshot saved for order {} customer {}", order.getId(), ctx.getCustomer().getPhone());
    }
}
