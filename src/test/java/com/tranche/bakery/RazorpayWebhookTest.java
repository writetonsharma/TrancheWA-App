package com.tranche.bakery;

import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.payment.RazorpayWebhookController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookTest extends FlowScenarioBase {

    @Autowired RazorpayWebhookController webhookController;
    @Autowired RazorpayService razorpayService;
    @Autowired OrderItemRepository orderItemRepository;

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test_webhook_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureVerification_matchesGood_rejectsBad() throws Exception {
        String body = "{\"event\":\"test\"}";
        assertThat(razorpayService.verifyWebhookSignature(body, sign(body))).isTrue();
        assertThat(razorpayService.verifyWebhookSignature(body, "deadbeef")).isFalse();
    }

    @Test
    void badSignature_isRejectedWith400() {
        ResponseEntity<String> r = webhookController.handle("{\"event\":\"payment_link.paid\"}", "deadbeef");
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void paymentLinkPaid_confirmsTheOrder() throws Exception {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PENDING_PAYMENT_SCREENSHOT);
        order.setTotalAmount(new BigDecimal("400"));
        order.setDeliveryDate(LocalDate.now().plusDays(1));
        order = orderRepository.save(order);
        OrderItem it = new OrderItem();
        it.setOrder(order);
        it.setMenuItem(lemon);
        it.setQuantity(1);
        it.setUnitPrice(new BigDecimal("400"));
        it.setSubtotal(new BigDecimal("400"));
        orderItemRepository.save(it);

        String body = "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":"
                + "{\"notes\":{\"kind\":\"ORDER\",\"orderId\":\"" + order.getId() + "\"}}}}}";
        ResponseEntity<String> r = webhookController.handle(body, sign(body));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
    }
}
