package com.tranche.bakery;

import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderChannel;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.payment.RazorpayWebhookController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Local database and signed fixture bodies only; never calls a payment provider. */
class WebWebhookSafetyTest extends FlowScenarioBase {
    @Autowired AdminService admin;
    @Autowired RazorpayWebhookController webhook;
    @Autowired PaymentRepository payments;

    @Test
    void ordinaryAdminDateEditCannotMutateAcceptedWebSnapshot() {
        Order order = attempt("PAYABLE", "plink_saved");
        LocalDate accepted = order.getDeliveryDate();

        // The admin controller does not catch service exceptions on this form submission.
        org.assertj.core.api.Assertions.assertThatCode(() -> admin.updateOrderDeliveryDate(
                Objects.requireNonNull(order.getId()), accepted.plusDays(1))).doesNotThrowAnyException();

        assertThat(reload(order).getDeliveryDate()).isEqualTo(accepted);
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(payment(order).getGatewayLinkId()).isEqualTo("plink_saved");
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(sentTexts).isEmpty();
    }

    @Test
    void ordinaryAdminDateEditStillUpdatesWhatsappOrders() {
        Order order = attempt("PAYABLE", "plink_saved");
        order.setChannel(OrderChannel.WHATSAPP);
        orderRepository.save(order);
        LocalDate changed = order.getDeliveryDate().plusDays(1);

        admin.updateOrderDeliveryDate(Objects.requireNonNull(order.getId()), changed);

        assertThat(reload(order).getDeliveryDate()).isEqualTo(changed);
        assertThat(sentTexts).anyMatch(text -> text.contains("Delivery date updated"));
    }

    @Test
    void signedEarlyWebhookBindsCreatingAndRetryIsIdempotent() throws Exception {
        Order order = attempt("CREATING", null);
        assertThat(post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid")))
                .isEqualTo(200);
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
        assertThat(jdbcTemplate.queryForObject("select provider_link_id from web_checkout_attempts where id='attempt'",
                String.class)).isEqualTo("plink_valid");
        post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid"));
        assertThat(payment(order).getGatewayPaymentId()).isEqualTo("pay_valid");
        assertThat(customerRepository.findById(Objects.requireNonNull(customer.getId())).orElseThrow().getCreditBalance())
                .isEqualByComparingTo("50");
    }

    @Test
    void signedEarlyUnknownWebhookCanBind() throws Exception {
        Order order = attempt("UNKNOWN", null);
        post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid"));
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
    }

    @Test
    void ordinarySignedOrderNotesCannotBypassWebBindingEvenWithSavedLink() throws Exception {
        Order order = attempt("PAYABLE", "plink_saved");
        post(body(order, "40000", "INR", null, null, "plink_saved", "pay_valid"));
        review(order);
        assertThat(payment(order).getGatewayLinkId()).isEqualTo("plink_saved");
    }

    @Test
    void mismatchedReferenceAndAttemptCannotBind() throws Exception {
        Order order = attempt("CREATING", null);
        post(body(order, "40000", "INR", "wrong", "wrong", "plink_valid", "pay_valid"));
        review(order);
        assertThat(payment(order).getGatewayLinkId()).isNull();
        assertThat(jdbcTemplate.queryForObject("select provider_link_id from web_checkout_attempts where id='attempt'",
                String.class)).isNull();
    }

    @Test
    void conflictingLinkNeverOverwritesSavedLink() throws Exception {
        Order order = attempt("PAYABLE", "plink_saved");
        post(body(order, "40000", "INR", "attempt", "reference", "plink_other", "pay_valid"));
        review(order);
        assertThat(payment(order).getGatewayLinkId()).isEqualTo("plink_saved");
    }

    @Test
    void decimalsCoercionsCurrenciesAndInvalidPaymentIdsNeverCaptureOrBind() throws Exception {
        Order order = attempt("CREATING", null);
        for (String amount : new String[]{"40000.9", "40000.0", "\"40000\"", "-40000", "0", "null",
                "999999999999999999999999999999999999999999"}) {
            post(body(order, amount, "INR", "attempt", "reference", "plink_valid", "pay_valid"));
            review(order);
            assertThat(payment(order).getGatewayLinkId()).isNull();
        }
        for (String currency : new String[]{null, "USD", "inr"}) {
            post(body(order, "40000", currency, "attempt", "reference", "plink_valid", "pay_valid"));
            review(order);
        }
        post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", ""));
        review(order);
        assertThat(payment(order).getGatewayLinkId()).isNull();
    }

    @Test
    void expiredAndCutoffCancelledNeverCaptureOrBind() throws Exception {
        Order order = attempt("UNKNOWN", null);
        jdbcTemplate.update("update web_checkout_attempts set expires_at=now()-interval '1 minute'");
        post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid"));
        review(order);
        jdbcTemplate.update("update web_checkout_attempts set expires_at=now()+interval '10 minutes'");
        order = reload(order);
        order.setStatus(OrderStatus.CANCELLED);
        order.setCutoffCancelled(true);
        orderRepository.save(order);
        post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid"));
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.REVIEW_REQUIRED);
        assertThat(payment(order).getGatewayLinkId()).isNull();
    }

    @Test
    void cutoffOverloadOnlyTrustsPersistedLinkAndScreenshotCannotConfirmWeb() {
        Order order = attempt("CREATING", null);
        admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()), "pay_valid", "plink_valid", new BigDecimal("400"));
        review(order);
        admin.approvePayment(Objects.requireNonNull(order.getId()));
        review(order);
        jdbcTemplate.update("update web_checkout_attempts set state='PAYABLE',provider_link_id='plink_saved'");
        Payment saved = payment(order);
        saved.setGatewayLinkId("plink_saved");
        payments.save(saved);
        admin.confirmGatewayPayment(Objects.requireNonNull(order.getId()), "pay_valid", "plink_saved", new BigDecimal("400"));
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
    }

    @Test
    void invalidSignatureNeverBinds() {
        Order order = attempt("CREATING", null);
        assertThat(webhook.handle(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid"),
                "invalid").getStatusCode().value()).isEqualTo(400);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment(order).getGatewayLinkId()).isNull();
    }

    @Test
    void exactReferenceIdAndNotesReferenceMustBothMatch() throws Exception {
        Order order = attempt("CREATING", null);
        String signed = body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid")
                .replace("\"webReference\":\"reference\"", "\"webReference\":\"different\"");
        post(signed);
        review(order);
        assertThat(payment(order).getGatewayLinkId()).isNull();
    }

    @Test
    void wrongAmountAndInvalidLinkNeverBind() throws Exception {
        Order order = attempt("CREATING", null);
        post(body(order, "39999", "INR", "attempt", "reference", "plink_valid", "pay_valid"));
        review(order);
        post(body(order, "40000", "INR", "attempt", "reference", "not_a_link", "pay_valid"));
        review(order);
        assertThat(payment(order).getGatewayLinkId()).isNull();
    }

    @Test
    void readyAttemptIsNeverProviderBindable() throws Exception {
        Order order = attempt("READY", null);
        post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid"));
        review(order);
        assertThat(payment(order).getGatewayLinkId()).isNull();
    }

    @Test
    void conflictingSecondPaymentRequiresReviewAndPreservesOriginalProviderIds() throws Exception {
        Order order = attempt("PAYABLE", "plink_saved");
        post(body(order, "40000", "INR", "attempt", "reference", "plink_saved", "pay_first"));
        post(body(order, "40000", "INR", "attempt", "reference", "plink_saved", "pay_second"));
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.REVIEW_REQUIRED);
        assertThat(payment(order).getGatewayPaymentId()).isEqualTo("pay_first");
        assertThat(payment(order).getGatewayLinkId()).isEqualTo("plink_saved");
    }

    @Test
    void invalidWebCreditSnapshotIsNotConsumedOrCaptured() throws Exception {
        Order order = attempt("CREATING", null);
        order.setCreditApplied(new BigDecimal("10"));
        orderRepository.save(order);
        post(body(order, "40000", "INR", "attempt", "reference", "plink_valid", "pay_valid"));
        review(order);
        assertThat(customerRepository.findById(Objects.requireNonNull(customer.getId())).orElseThrow().getCreditBalance())
                .isEqualByComparingTo("50");
    }

    private Order attempt(String state, String link) {
        customer.setCreditBalance(new BigDecimal("50"));
        customerRepository.save(customer);
        Order order = new Order();
        order.setCustomer(customer);
        order.setChannel(OrderChannel.WEB);
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setTotalAmount(new BigDecimal("400"));
        order.setDeliveryDate(LocalDate.now().plusDays(1));
        order = orderRepository.save(order);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(new BigDecimal("400"));
        payment.setProvider("RAZORPAY");
        payment.setGatewayLinkId(link);
        payments.save(payment);
        jdbcTemplate.update("""
                insert into web_checkout_attempts
                (id,customer_id,order_id,idempotency_key,request_hash,cart_version,quote_token_hash,quote_hash,
                 snapshot,state,provider_reference,provider_link_id,expires_at,created_at,updated_at)
                values ('attempt',?,?,'test_key_1234567890',?,1,?,?,'{}'::jsonb,?,'reference',?,
                        now()+interval '10 minutes',now(),now())
                """, customer.getId(), order.getId(), "a".repeat(64), "b".repeat(64), "c".repeat(64), state, link);
        return order;
    }

    private Order reload(Order order) { return orderRepository.findById(Objects.requireNonNull(order.getId())).orElseThrow(); }
    private Payment payment(Order order) { return payments.findByOrder(reload(order)).orElseThrow(); }
    private void review(Order order) {
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_REVIEW_REQUIRED);
        assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.REVIEW_REQUIRED);
    }

    private int post(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test_webhook_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return webhook.handle(body, HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))))
                .getStatusCode().value();
    }

    private String body(Order order, String amount, String currency, String attempt, String reference,
                        String link, String payment) {
        return "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + link
                + "\",\"amount_paid\":" + amount + (currency == null ? "" : ",\"currency\":\"" + currency + "\"")
                + (reference == null ? "" : ",\"reference_id\":\"" + reference + "\"")
                + ",\"notes\":{\"kind\":\"ORDER\",\"orderId\":\"" + order.getId() + "\""
                + (attempt == null ? "" : ",\"webAttemptId\":\"" + attempt + "\",\"webReference\":\"" + reference + "\"")
                + "}}},\"payment\":{\"entity\":{\"id\":\"" + payment + "\"}}}}";
    }
}