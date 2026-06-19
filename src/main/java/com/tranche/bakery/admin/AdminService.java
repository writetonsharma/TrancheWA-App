package com.tranche.bakery.admin;

import com.tranche.bakery.alert.AlertRepository;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.feedback.FeedbackRepository;
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
    private final FeedbackRepository feedbackRepository;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final WhatsAppClient whatsAppClient;

    @Transactional(readOnly = true)
    public AdminDashboard buildDashboard() {
        LocalDate today = LocalDate.now();

        List<AdminOrderView> deliveringToday = loadViews(
                orderRepository.findAllByStatusAndDeliveryDateOrderByDeliveryDateAsc(
                        OrderStatus.CONFIRMED, today));

        List<AdminOrderView> deliveringTomorrow = loadViews(
                orderRepository.findAllByStatusAndDeliveryDateOrderByDeliveryDateAsc(
                        OrderStatus.CONFIRMED, today.plusDays(1)));

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
                paymentReview, stuckDrafts, awaitingScreenshot,
                feedbackRepository.findAllByOrderByCreatedAtDesc(),
                alertRepository.findAllByResolvedFalseOrderByCreatedAtDesc());
    }

    @Transactional
    public void resolveAllAlerts() {
        alertService.resolveAll();
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
                        "Your payment has been verified and your order is confirmed. " +
                        "Your bake will be scheduled for the next available slot. Thank you for ordering from Tranché Bakery.");
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

    @Transactional
    public void markInBaking(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.IN_BAKING);
            orderRepository.save(order);
            try {
                String ref = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
                whatsAppClient.sendText(order.getCustomer().getPhone(),
                        "🔥 *Great news — your order is being baked right now!*\n\n" +
                        "Order *" + ref + "* is in the oven. " +
                        "We'll deliver between 6–8 AM tomorrow morning. 🥖");
            } catch (Exception e) {
                log.warn("Could not notify customer after marking in baking: {}", e.getMessage());
            }
            log.info("Admin marked order {} as IN_BAKING", orderId);
        });
    }

    public byte[] getQrImage(Long orderId) {
        return orderRepository.findById(orderId)
                .flatMap(paymentRepository::findByOrder)
                .map(p -> p.getQrImageData())
                .orElse(null);
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
