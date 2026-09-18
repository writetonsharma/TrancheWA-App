package com.tranche.bakery;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;

class CreditFlowTest extends FlowScenarioBase {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AdminService adminService;

    @Test
    void credit_partialReducesTotalAndIsConsumedOnApproval() {
        customer.setCreditBalance(new BigDecimal("30"));
        customer = customerRepository.save(customer);

        Long orderId = driveToPaymentQr();
        Order order = orderRepository.findById(orderId).orElseThrow();

        assertThat(order.getCreditApplied()).as("₹30 credit applied (order total exceeds it)")
                .isEqualByComparingTo("30");
        Payment payment = paymentRepository.findByOrder(order).orElseThrow();
        assertThat(payment.getAmount()).as("QR amount matches the credit-reduced total")
                .isEqualByComparingTo(order.getTotalAmount());
        assertThat(sentTexts).anyMatch(t -> t.contains("Credit applied"));

        adminService.approvePayment(orderId);

        Customer reloaded = customerRepository.findById(customer.getId()).orElseThrow();
        assertThat(reloaded.getCreditBalance()).as("balance drops by the ₹30 used")
                .isEqualByComparingTo("0");
    }

    @Test
    void credit_fullyCoversOrder_routesToReviewWithNoCharge() {
        customer.setCreditBalance(new BigDecimal("100000"));
        customer = customerRepository.save(customer);

        String catId = firstCategoryId();
        String itemId = firstItemId(catId);
        send("hi");
        send("order");
        send(nextDeliveryDate());
        send(catId);
        send(itemId);
        send("1");
        send("view_order");
        send("use_address");
        send("pref_gate");
        send("loaf_sliced");
        send("confirm");

        List<Order> orders = orderRepository.findAllByCustomerIdOrderByCreatedAtDesc(customer.getId());
        Order order = orders.get(0);
        assertThat(order.getTotalAmount()).as("credit covers it fully").isEqualByComparingTo("0");
        assertThat(order.getCreditApplied()).isGreaterThan(BigDecimal.ZERO);
        assertThat(order.getStatus()).as("routed to admin review, not a ₹0 QR")
                .isEqualTo(OrderStatus.PAYMENT_REVIEW_REQUIRED);
        assertThat(sentTexts).anyMatch(t -> t.contains("fully covered by your account credit"));
        assertThat(paymentRepository.findByOrder(order)).as("no payment/QR created").isEmpty();
    }

    @Test
    void noCredit_leavesTotalUntouched() {
        Long orderId = driveToPaymentQr();
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getCreditApplied()).isEqualByComparingTo("0");
    }

    @Test
    void credit_shownInMainMenuGreeting() {
        customer.setCreditBalance(new BigDecimal("50"));
        customer = customerRepository.save(customer);

        send("hi");

        assertThat(sentButtonBodies).as("greeting nudges the customer about their credit")
                .anyMatch(b -> b.contains("in credit"));
    }
}
