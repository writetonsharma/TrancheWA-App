package com.tranche.bakery.admin;

import com.tranche.bakery.order.*;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final WhatsAppClient whatsAppClient;

    @Transactional(readOnly = true)
    public AdminDashboard buildDashboard() {
        LocalDate today = LocalDate.now();

        List<AdminOrderView> deliveringToday = loadViews(
                orderRepository.findConfirmedBetween(
                        OrderStatus.CONFIRMED,
                        today.minusDays(1).atStartOfDay(),
                        today.atStartOfDay()));

        List<AdminOrderView> deliveringTomorrow = loadViews(
                orderRepository.findConfirmedBetween(
                        OrderStatus.CONFIRMED,
                        today.atStartOfDay(),
                        today.plusDays(1).atStartOfDay()));

        List<AdminOrderView> paymentReview = loadViews(
                orderRepository.findAllByStatusIn(
                        Set.of(OrderStatus.PAYMENT_SCREENSHOT_RECEIVED,
                               OrderStatus.PAYMENT_REVIEW_REQUIRED)));

        List<AdminOrderView> stuckDrafts = loadViews(
                orderRepository.findAllByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        OrderStatus.DRAFT, LocalDateTime.now().minusHours(2)));

        List<AdminOrderView> awaitingScreenshot = loadViews(
                orderRepository.findAllByStatusOrderByCreatedAtDesc(
                        OrderStatus.PENDING_CONFIRMATION));

        return new AdminDashboard(
                today, deliveringToday, deliveringTomorrow,
                paymentReview, stuckDrafts, awaitingScreenshot);
    }

    @Transactional
    public void approvePayment(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            paymentRepository.findByOrder(order).ifPresent(payment -> {
                payment.setStatus(com.tranche.bakery.payment.PaymentStatus.SCREENSHOT_VERIFIED);
                paymentRepository.save(payment);
            });
            try {
                whatsAppClient.sendText(order.getCustomer().getPhone(),
                        "✅ Your payment has been verified and your order is confirmed! " +
                        "We'll deliver to you on the next bake day. Thank you for ordering from Tranché Bakery! 🥖");
            } catch (Exception e) {
                log.warn("Could not notify customer after payment approval: {}", e.getMessage());
            }
            log.info("Admin approved payment for order {}", orderId);
        });
    }

    @Transactional
    public void flagForReview(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.PAYMENT_REVIEW_REQUIRED);
            orderRepository.save(order);
            log.info("Admin flagged order {} for payment review", orderId);
        });
    }

    public void sendMessage(String phone, String message) {
        whatsAppClient.sendText(phone, message);
        log.info("Admin sent message to {}", phone);
    }

    private List<AdminOrderView> loadViews(List<Order> orders) {
        return orders.stream()
                .map(o -> AdminOrderView.of(
                        o,
                        orderItemRepository.findAllByOrderId(o.getId()),
                        paymentRepository.findByOrder(o).orElse(null)))
                .toList();
    }
}
