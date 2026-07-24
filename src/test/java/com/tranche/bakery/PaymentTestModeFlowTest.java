package com.tranche.bakery;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;

/**
 * Verifies the payment test-mode toggle (bakery.payment.test-mode=true).
 *
 * In test mode the QR/UPI collects only a token amount (a random rupee value in
 * [1.00, 1.99]) so real money can be exchanged cheaply during live testing. This
 * must never leak into the order total: the bill/receipt has to reflect the real
 * cart amount. These tests lock that decoupling in place.
 */
@TestPropertySource(properties = "bakery.payment.test-mode=true")
class PaymentTestModeFlowTest extends FlowScenarioBase {

    @Autowired private PaymentRepository paymentRepository;

    @Test
    void testMode_qrChargesTokenAmount_butOrderTotalStaysReal() {
        Long orderId = driveToPaymentQr();

        Order order = orderRepository.findById(orderId).orElseThrow();
        Payment payment = paymentRepository.findByOrder(order).orElseThrow();

        // The QR/payment record collects only the token amount...
        assertThat(payment.getAmount())
                .as("test-mode QR charges a token rupee amount")
                .isBetween(new BigDecimal("1.00"), new BigDecimal("1.99"));

        // ...while the order total remains the real cart amount, untouched by test mode.
        assertThat(order.getTotalAmount())
                .as("order total is the real cart amount, not the token")
                .isGreaterThan(new BigDecimal("2.00"));

        assertThat(order.getTotalAmount())
                .as("order total and token QR amount are decoupled")
                .isNotEqualByComparingTo(payment.getAmount());
    }

    @Test
        void testMode_screenshotAwaitsVerificationAndKeepsRealOrderTotal() {
        Long orderId = driveToPaymentQr();

        Order before = orderRepository.findById(orderId).orElseThrow();
        BigDecimal realTotal = before.getTotalAmount();

        // Customer pays the token amount and shares the screenshot -> verification is pending.
        sendImage("media-test-token");

        assertState("IDLE");
        assertOrderStatus(orderId, OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);

        // The order still carries the real total that its eventual receipt will render,
        // never the token QR amount.
        Order after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.getTotalAmount())
                .as("order total is unchanged and real while verification is pending")
                .isEqualByComparingTo(realTotal)
                .isGreaterThan(new BigDecimal("2.00"));

        assertThat(sentTexts).anyMatch(text -> text.contains("Payment screenshot received")
                && text.contains("another message")
                && text.contains("No further action"));
    }
}
