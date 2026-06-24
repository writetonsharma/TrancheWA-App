package com.tranche.bakery;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;

class PricingOverrideFlowTest extends FlowScenarioBase {

    @Autowired private PaymentRepository paymentRepository;

    @Test
    void pricingOverride_perItemFlatRate() {
        customer.setPricingOverride(new BigDecimal("150.00"));
        customer.setFreeDelivery(true);
        customer.setOverrideExpiresAt(null);
        customer = customerRepository.save(customer);

        Long orderId = driveToPaymentQr();

        Order order = orderRepository.findById(orderId).orElseThrow();
        // 1 item × ₹150 per-item rate + ₹0 delivery = ₹150
        assertThat(order.getTotalAmount()).as("total should be qty × override rate")
                .isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(order.getDeliveryCharge()).as("free delivery")
                .isEqualByComparingTo(BigDecimal.ZERO);

        Payment payment = paymentRepository.findByOrder(order).orElseThrow();
        assertThat(payment.getAmount()).as("QR amount matches order total")
                .isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void pricingOverride_expiredOverrideUsesNormalTotal() {
        customer.setPricingOverride(new BigDecimal("150.00"));
        customer.setOverrideExpiresAt(LocalDateTime.now().minusDays(1));
        customer = customerRepository.save(customer);

        Long orderId = driveToPaymentQr();

        Order order = orderRepository.findById(orderId).orElseThrow();
        Payment payment = paymentRepository.findByOrder(order).orElseThrow();
        assertThat(payment.getAmount()).as("expired override should use order total")
                .isEqualByComparingTo(order.getTotalAmount());
    }

    @Test
    void pricingOverride_noOverrideUsesNormalTotal() {
        Long orderId = driveToPaymentQr();

        Order order = orderRepository.findById(orderId).orElseThrow();
        Payment payment = paymentRepository.findByOrder(order).orElseThrow();
        assertThat(payment.getAmount()).as("no override should use order total")
                .isEqualByComparingTo(order.getTotalAmount());
    }

    @Test
    void deliveryCharge_includedInOrderTotal() {
        Long orderId = driveToPaymentQr();

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getDeliveryCharge()).as("delivery charge should be ₹50")
                .isEqualByComparingTo(new BigDecimal("50"));
        assertThat(order.getTotalAmount()).as("total should include delivery charge")
                .isGreaterThan(new BigDecimal("50"));
    }

    @Test
    void deliveryCharge_freeDeliveryCustomerGetsZeroCharge() {
        customer.setFreeDelivery(true);
        customer = customerRepository.save(customer);

        Long orderId = driveToPaymentQr();

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getDeliveryCharge()).as("free delivery customer gets ₹0 charge")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
