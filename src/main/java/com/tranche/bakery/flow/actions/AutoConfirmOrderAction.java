package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoConfirmOrderAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "AUTO_CONFIRM_ORDER"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) return;

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) return;

        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        paymentRepository.findByOrder(order).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.SCREENSHOT_VERIFIED);
            paymentRepository.save(payment);
        });

        String orderNumber = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();

        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "✅ *Your order is confirmed!* 🥖\n\n" +
                "Your order reference is *" + orderNumber + "*.\n\n" +
                "We've received your payment screenshot and your order is all set. " +
                "We'll verify the payment when we begin preparing your order.\n\n" +
                "Thank you for choosing Tranché Bakery — we can't wait to bake for you! 🧡");

        log.info("Order {} auto-confirmed for customer {}", orderNumber, ctx.getCustomer().getPhone());
    }
}
