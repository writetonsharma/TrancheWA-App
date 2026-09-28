package com.tranche.bakery.payment;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cancelled order must not leave a payable link behind. If it does, the customer can still pay a
 * link for an order that no longer exists and we are holding money against nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentLinkRevocationTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean RazorpayService razorpayService;

    private Customer customer;
    private Order order;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE payment_screenshots, payments, order_items, orders, " +
                "whatsapp_conversations, customers RESTART IDENTITY CASCADE");

        when(razorpayService.isConfigured()).thenReturn(true);

        customer = new Customer();
        customer.setPhone("919000000001");
        customer.setName("Test User");
        customer = customerRepository.save(customer);

        order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setTotalAmount(new BigDecimal("450.00"));
        order = orderRepository.save(order);
    }

    private Payment livePayment(PaymentStatus status) {
        Payment p = new Payment();
        p.setOrder(order);
        p.setProvider("RAZORPAY");
        p.setAmount(new BigDecimal("450.00"));
        p.setStatus(status);
        p.setGatewayLinkId("plink_TEST123");
        p.setGatewayLinkUrl("https://rzp.io/i/test123");
        return paymentRepository.save(p);
    }

    @Test
    void customerCancel_killsTheLiveLink() throws Exception {
        livePayment(PaymentStatus.PENDING);

        boolean cancelled = orderService.cancelByIdForCustomer(order.getId(), customer.getId());

        assertThat(cancelled).isTrue();
        verify(razorpayService).cancelPaymentLink("plink_TEST123");

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        Payment after = paymentRepository.findById(1L).orElseThrow();
        assertThat(after.getGatewayLinkId()).isNull();
        assertThat(after.getGatewayLinkUrl()).isNull();
    }

    /**
     * A gateway outage must not silently drop the link: the id stays so the next payment prompt
     * retries the cancel instead of minting a second live link alongside the first.
     */
    @Test
    void failedRevoke_keepsLinkForRetry_butStillCancelsOrder() throws Exception {
        livePayment(PaymentStatus.PENDING);
        doThrow(new RuntimeException("gateway down")).when(razorpayService).cancelPaymentLink(anyString());

        boolean cancelled = orderService.cancelByIdForCustomer(order.getId(), customer.getId());

        assertThat(cancelled).isTrue();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        Payment after = paymentRepository.findById(1L).orElseThrow();
        assertThat(after.getGatewayLinkId()).isEqualTo("plink_TEST123");
    }

    /** Money already taken. Cancelling that link would be cancelling a completed payment. */
    @Test
    void capturedPayment_isNeverRevoked() throws Exception {
        livePayment(PaymentStatus.GATEWAY_CAPTURED);

        orderService.cancelByIdForCustomer(order.getId(), customer.getId());

        verify(razorpayService, never()).cancelPaymentLink(anyString());
        assertThat(paymentRepository.findById(1L).orElseThrow().getGatewayLinkId())
                .isEqualTo("plink_TEST123");
    }

    /** Someone else's order id must not cancel their order or kill their link. */
    @Test
    void cancelIsRefused_forSomeoneElsesOrder() throws Exception {
        livePayment(PaymentStatus.PENDING);

        Customer other = new Customer();
        other.setPhone("919000000002");
        other.setName("Other User");
        other = customerRepository.save(other);

        boolean cancelled = orderService.cancelByIdForCustomer(order.getId(), other.getId());

        assertThat(cancelled).isFalse();
        verify(razorpayService, never()).cancelPaymentLink(anyString());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_CONFIRMATION);
    }

    /** Already-cancelled orders must not be re-cancelled into a second revoke attempt. */
    @Test
    void cancelIsRefused_whenOrderAlreadyCancelled() throws Exception {
        livePayment(PaymentStatus.PENDING);
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        boolean cancelled = orderService.cancelByIdForCustomer(order.getId(), customer.getId());

        assertThat(cancelled).isFalse();
        verify(razorpayService, never()).cancelPaymentLink(anyString());
    }
}
