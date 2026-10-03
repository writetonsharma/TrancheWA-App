package com.tranche.bakery;

import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
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
    @Autowired PaymentRepository paymentRepository;
    @Autowired com.tranche.bakery.alert.AlertRepository alertRepository;

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
    void paymentLinkPaid_withoutAmount_isHeldForReviewNotConfirmed() throws Exception {
        Order order = pendingOrderOf("400");

        String body = "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":"
                + "{\"notes\":{\"kind\":\"ORDER\",\"orderId\":\"" + order.getId() + "\"}}}}}";
        ResponseEntity<String> r = post(body);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_REVIEW_REQUIRED);
    }

    @Test
    void paymentLinkPaid_zeroAmount_isHeldForReviewNotConfirmed() throws Exception {
        Order order = pendingOrderOf("400");

        ResponseEntity<String> r = post(paidBody(order.getId(), 0, "pay_ZERO", "plink_ZERO"));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_REVIEW_REQUIRED);
    }

    @Test
    void paymentLinkPaid_matchingAmount_confirmsAndStoresGatewayReference() throws Exception {
        Order order = pendingOrderOf("400");

        ResponseEntity<String> r = post(paidBody(order.getId(), 40000, "pay_ABC", "plink_XYZ"));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        var payment = paymentRepository.findByOrder(orderRepository.findById(order.getId()).orElseThrow())
                .orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
        assertThat(payment.getGatewayPaymentId()).isEqualTo("pay_ABC");
        assertThat(payment.getGatewayLinkId()).isEqualTo("plink_XYZ");
        assertThat(payment.getProvider()).isEqualTo("RAZORPAY");
        // Admin is pinged that a paid order landed (gateway mode has no screenshot to prompt them).
        assertThat(alertRepository.findAll()).extracting(a -> a.getType()).contains("ORDER_PAID");
    }

    @Test
    void paymentLinkPaid_underpaid_holdsForReviewInsteadOfConfirming() throws Exception {
        Order order = pendingOrderOf("400");

        ResponseEntity<String> r = post(paidBody(order.getId(), 30000, "pay_SHORT", "plink_SHORT"));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_REVIEW_REQUIRED);
    }

    @Test
    void paymentLinkPaid_forCutoffCancelledOrder_doesNotResurrectIt() throws Exception {
        Order order = pendingOrderOf("400");
        order.setStatus(OrderStatus.CANCELLED);
        order.setCutoffCancelled(true);
        orderRepository.save(order);

        ResponseEntity<String> r = post(paidBody(order.getId(), 40000, "pay_LATE", "plink_LATE"));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    private ResponseEntity<String> post(String body) throws Exception {
        return webhookController.handle(body, sign(body));
    }

    private static String paidBody(Long orderId, long amountPaise, String paymentId, String linkId) {
        return "{\"event\":\"payment_link.paid\",\"payload\":{"
                + "\"payment_link\":{\"entity\":{\"id\":\"" + linkId + "\",\"amount_paid\":" + amountPaise
                + ",\"notes\":{\"kind\":\"ORDER\",\"orderId\":\"" + orderId + "\"}}},"
                + "\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\"}}}}";
    }

    private Order pendingOrderOf(String total) {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PENDING_PAYMENT_SCREENSHOT);
        order.setTotalAmount(new BigDecimal(total));
        order.setDeliveryDate(LocalDate.now().plusDays(1));
        order = orderRepository.save(order);
        OrderItem it = new OrderItem();
        it.setOrder(order);
        it.setMenuItem(lemon);
        it.setQuantity(1);
        it.setUnitPrice(new BigDecimal(total));
        it.setSubtotal(new BigDecimal(total));
        orderItemRepository.save(it);
        return order;
    }
}
