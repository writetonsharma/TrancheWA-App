package com.tranche.bakery.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.alert.AlertRepository;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.feedback.FeedbackRepository;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final CustomerRepository customerRepository;
    private final AdminMessageRepository adminMessageRepository;

    @Transactional(readOnly = true)
    public AdminDashboard buildDashboard() {
        LocalDate today = LocalDate.now();

        List<AdminOrderView> deliveringToday = loadViews(
                orderRepository.findAllByStatusInAndDeliveryDateOrderByDeliveryDateAsc(
                        Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING), today));

        List<AdminOrderView> deliveringTomorrow = loadViews(
                orderRepository.findAllByStatusInAndDeliveryDateOrderByDeliveryDateAsc(
                        Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING), today.plusDays(1)));

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

        List<BakeListItem> bakeListTomorrow = buildBakeList(today.plusDays(1));

        List<AdminOrderView> orderHistory = loadViews(
                orderRepository.findAllByStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(
                        Set.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED),
                        LocalDateTime.now().minusDays(7)));

        List<AdminOrderView> futureDeliveries = loadViews(
                orderRepository.findAllByStatusInAndDeliveryDateBetweenOrderByDeliveryDateAsc(
                        Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING),
                        today.plusDays(2), today.plusDays(6)));

        return new AdminDashboard(
                today, deliveringToday, deliveringTomorrow,
                paymentReview, stuckDrafts, awaitingScreenshot,
                feedbackRepository.findAllByOrderByCreatedAtDesc(),
                alertRepository.findAllByResolvedFalseOrderByCreatedAtDesc(),
                bakeListTomorrow, orderHistory, futureDeliveries);
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

    @Transactional
    public void sendMessage(String phone, String message) {
        whatsAppClient.sendText(phone, message);
        customerRepository.findByPhone(phone).ifPresent(customer -> {
            AdminMessage msg = new AdminMessage();
            msg.setCustomer(customer);
            msg.setDirection(AdminMessage.Direction.OUTBOUND);
            msg.setMessage(message);
            adminMessageRepository.save(msg);
        });
        log.info("Admin sent message to {}", phone);
    }

    @Transactional
    public void updateCustomerDetails(String phone, String name, String deliveryArea,
                                      String deliveryAddress, String locationLat, String locationLng) {
        customerRepository.findByPhone(phone).ifPresent(customer -> {
            customer.setName(blankToNull(name));
            customer.setDeliveryArea(blankToNull(deliveryArea));
            customer.setDeliveryAddress(blankToNull(deliveryAddress));
            customer.setLocationLat(parseDecimal(locationLat));
            customer.setLocationLng(parseDecimal(locationLng));
            customerRepository.save(customer);
            log.info("Admin updated customer details for {}", phone);
        });
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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

    @Transactional
    public void markCompleted(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);
            try {
                String ref = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
                whatsAppClient.sendText(order.getCustomer().getPhone(),
                        "✅ Your order *" + ref + "* has been delivered! " +
                        "Thank you for choosing Tranché Bakery. We hope you enjoy it! 🥖\n\n" +
                        "Send *hi* to place a new order anytime.");
            } catch (Exception e) {
                log.warn("Could not notify customer after marking order completed: {}", e.getMessage());
            }
            log.info("Admin marked order {} as COMPLETED", orderId);
        });
    }

    @Transactional
    public void cancelOrder(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            try {
                String ref = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
                whatsAppClient.sendText(order.getCustomer().getPhone(),
                        "Your order *" + ref + "* has been cancelled. " +
                        "If you have any questions, please message us.\n\nSend *hi* to place a new order. 🥖");
            } catch (Exception e) {
                log.warn("Could not notify customer after order cancellation: {}", e.getMessage());
            }
            log.info("Admin cancelled order {}", orderId);
        });
    }

    public byte[] getQrImage(Long orderId) {
        return orderRepository.findById(orderId)
                .flatMap(paymentRepository::findByOrder)
                .map(p -> p.getQrImageData())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AdminOrderView> searchOrders(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.trim();
        List<Customer> customers = customerRepository.findByPhoneContainingOrNameContainingIgnoreCase(q, q);
        if (customers.isEmpty()) return List.of();
        List<Long> customerIds = customers.stream().map(Customer::getId).toList();
        return loadViews(orderRepository.findAllByCustomerIdInOrderByCreatedAtDesc(customerIds));
    }

    @Transactional(readOnly = true)
    public ConversationThread getConversation(String phone) {
        Customer customer = customerRepository.findByPhone(phone).orElse(null);
        if (customer == null) return null;
        List<AdminMessage> messages = adminMessageRepository.findAllByCustomerIdOrderByCreatedAtAsc(customer.getId());
        return new ConversationThread(customer, messages);
    }

    @Transactional(readOnly = true)
    public List<BakeListItem> buildBakeList(LocalDate date) {
        List<Order> orders = orderRepository.findAllByStatusInAndDeliveryDateOrderByDeliveryDateAsc(
                Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING), date);

        Map<String, int[]> aggregation = new LinkedHashMap<>();
        for (Order order : orders) {
            for (OrderItem item : orderItemRepository.findAllByOrderId(order.getId())) {
                String name = item.getMenuItem().getName();
                aggregation.computeIfAbsent(name, k -> new int[]{0, 0});
                aggregation.get(name)[0] += item.getQuantity();
                aggregation.get(name)[1] += 1;
            }
        }

        return aggregation.entrySet().stream()
                .map(e -> new BakeListItem(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingInt(BakeListItem::totalQuantity).reversed())
                .toList();
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
