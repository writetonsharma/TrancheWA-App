package com.tranche.bakery.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.tranche.bakery.alert.AlertRepository;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.feedback.FeedbackRepository;
import com.tranche.bakery.order.FulfillmentType;
import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderChannel;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.whatsapp.CustomerNotifier;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.Predicate;
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
    private final CustomerRepository customerRepository;
    private final AdminMessageRepository adminMessageRepository;
    private final CustomerNotifier customerNotifier;
    private final CapacityLock capacityLock;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManagerFactory entityManagerFactory;
    private final PlatformTransactionManager transactionManager;

    private static final DateTimeFormatter ADMIN_DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM");

    @Transactional(readOnly = true)
    public AdminDashboard buildDashboard() {
        LocalDate today = LocalDate.now();

        List<AdminOrderView> deliveringToday = loadViews(
                orderRepository.findAllByStatusInAndDeliveryDateOrderByDeliveryDateAsc(
                        Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING, OrderStatus.OUT_FOR_DELIVERY), today));

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

        LocalDate soonEnd = today.plusDays(6);
        List<AdminOrderView> needingFix = loadViews(
                orderRepository.findAllByStatusIn(
                        Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING)))
                .stream()
                .filter(v -> {
                    LocalDate dd = v.order().getDeliveryDate();
                    if (dd == null) return true;
                    boolean missingPin = v.order().getFulfillmentType() == FulfillmentType.DELIVERY
                            && v.mapsUrl() == null;
                    boolean soon = !dd.isBefore(today) && !dd.isAfter(soonEnd);
                    return missingPin && soon;
                })
                .sorted(Comparator.comparing((AdminOrderView v) -> v.order().getCreatedAt()).reversed())
                .toList();

        return new AdminDashboard(
                today, deliveringToday, deliveringTomorrow,
                paymentReview, stuckDrafts, awaitingScreenshot,
                feedbackRepository.findAllByOrderByCreatedAtDesc(),
                alertRepository.findAllByResolvedFalseOrderByCreatedAtDesc(),
                bakeListTomorrow, orderHistory, futureDeliveries, needingFix);
    }

    @Transactional
    public void resolveAllAlerts() {
        alertService.resolveAll();
    }

    @Transactional
    public void resolveAlert(Long id) {
        alertService.resolve(id);
    }

    @Transactional
    public void approvePayment(@NonNull Long orderId) {
        capacityLock.acquire();
        Order candidate = orderRepository.findById(orderId).orElse(null);
        if (candidate == null) return;
        // WEB checkout is gateway-only. Screenshot approval must not bypass its binding checks.
        if (candidate.getChannel() == OrderChannel.WEB) {
            Payment payment = paymentRepository.findByOrder(candidate).orElse(null);
            if (payment != null) holdWebPayment(candidate, payment, "Screenshot approval is not valid for WEB checkout");
            return;
        }
        int flipped = orderRepository.confirmIfNotConfirmed(orderId, OrderStatus.CONFIRMED);
        orderRepository.findById(orderId).ifPresent(order -> {
            paymentRepository.findByOrder(order).ifPresent(payment -> {
                payment.setStatus(com.tranche.bakery.payment.PaymentStatus.SCREENSHOT_VERIFIED);
                paymentRepository.save(payment);
            });
            if (flipped == 1) {
                consumeCredit(order);
                customerNotifier.orderConfirmed(order);
            }
            log.info("Admin approved payment for order {}", orderId);
        });
    }

    // States where money is still outstanding, so a gateway payment may confirm the order. Anything
    // else (notably CANCELLED by the 5 PM cutoff) must never be resurrected by a late payment.
    private static final Set<OrderStatus> AWAITING_PAYMENT = EnumSet.of(
            OrderStatus.PENDING_CONFIRMATION,
            OrderStatus.PENDING_PAYMENT_SCREENSHOT,
            OrderStatus.PAYMENT_SCREENSHOT_RECEIVED,
            OrderStatus.PAYMENT_REVIEW_REQUIRED);

    /**
     * Confirms an order from an already signature-verified gateway webhook. A payment whose amount
     * doesn't match what we charged is held for review rather than auto-confirmed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void confirmGatewayPayment(@NonNull Long orderId, String gatewayPaymentId, String gatewayLinkId,
                                      BigDecimal paidAmount) {
        confirmGatewayPaymentAfterCommit(orderId, gatewayPaymentId, gatewayLinkId, paidAmount,
                null, null, null, false);
    }

    /** Signed webhook boundary. Missing WEB notes never fall back to the trusted cutoff path. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void confirmGatewayPayment(@NonNull Long orderId, String gatewayPaymentId, String gatewayLinkId,
                                      BigDecimal paidAmount, String webAttemptId,
                                      String providerReference, String currency) {
        confirmGatewayPaymentAfterCommit(orderId, gatewayPaymentId, gatewayLinkId, paidAmount,
                webAttemptId, providerReference, currency, true);
    }

    private void confirmGatewayPaymentAfterCommit(@NonNull Long orderId, String gatewayPaymentId,
                                                  String gatewayLinkId, BigDecimal paidAmount,
                                                  String webAttemptId, String providerReference,
                                                  String currency, boolean webhook) {
        List<Runnable> effects = new ArrayList<>();
        TransactionTemplate settlement = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        settlement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        settlement.executeWithoutResult(status -> confirmGatewayPaymentInternal(orderId, gatewayPaymentId,
                gatewayLinkId, paidAmount, webAttemptId, providerReference, currency, webhook, effects::add));
        // execute() returns only after commit AND resource cleanup/capacity release. NOT_SUPPORTED
        // keeps any caller transaction suspended until all effects finish (afterCommit alone cannot).
        // If settlement/commit throws, none of the collected effects are delivered.
        for (Runnable effect : effects) effect.run();
    }

    /**
     * Cutoff coordinator boundary: identical settlement guards, but no network under its capacity lock.
     * The caller must run the returned effects only after its enclosing transaction commits.
     */
    @Transactional
    public List<Runnable> confirmGatewayPaymentAtCutoff(@NonNull Long orderId, String gatewayPaymentId,
                                                       String gatewayLinkId, BigDecimal paidAmount) {
        List<Runnable> effects = new ArrayList<>();
        confirmGatewayPaymentInternal(orderId, gatewayPaymentId, gatewayLinkId, paidAmount,
                null, null, null, false, effects::add);
        return effects;
    }

    private void confirmGatewayPaymentInternal(@NonNull Long orderId, String gatewayPaymentId, String gatewayLinkId,
                                               BigDecimal paidAmount, String webAttemptId,
                                               String providerReference, String currency, boolean webhook,
                                               Consumer<Runnable> effects) {
        capacityLock.acquire();
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            effects.accept(() -> alertService.raise("PAYMENT_UNKNOWN_ORDER",
                    "Gateway payment " + gatewayPaymentId + " referenced unknown order " + orderId, orderId, null));
            return;
        }
        if (order.getChannel() == OrderChannel.WEB) {
            confirmWebPayment(order, gatewayPaymentId, gatewayLinkId, paidAmount,
                    webAttemptId, providerReference, currency, webhook, effects);
            return;
        }
        String customerPhone = order.getCustomer() != null ? order.getCustomer().getPhone() : null;

        Payment payment = paymentRepository.findByOrder(order).orElseGet(() -> {
            Payment p = new Payment();
            p.setOrder(order);
            return p;
        });
        // What we asked for, not the live order total: under payment test mode they differ.
        BigDecimal expected = payment.getAmount() != null ? payment.getAmount() : order.getTotalAmount();
        String priorPaymentId = payment.getGatewayPaymentId();

        if (payment.getGatewayLinkId() != null && !payment.getGatewayLinkId().equals(gatewayLinkId)) {
            holdWebPayment(order, payment, "Gateway link does not match the saved payment link", effects);
            return;
        }

        payment.setProvider("RAZORPAY");
        // Keep whichever payment actually settled the order; a second id means a duplicate charge.
        if (gatewayPaymentId != null && priorPaymentId == null) payment.setGatewayPaymentId(gatewayPaymentId);
        if (gatewayLinkId != null) payment.setGatewayLinkId(gatewayLinkId);

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            paymentRepository.save(payment);
            // Only a repeat of the same payment id is a harmless webhook retry. Money arriving against an
            // order settled any other way — including by approved screenshot, which leaves no payment id —
            // means the customer was charged twice.
            boolean sameChargeRetried = gatewayPaymentId != null && gatewayPaymentId.equals(priorPaymentId);
            if (gatewayPaymentId != null && !sameChargeRetried) {
                effects.accept(() -> alertService.raise("PAYMENT_DUPLICATE_CHARGE",
                        "Order " + orderId + " was already settled"
                                + (priorPaymentId != null ? " by " + priorPaymentId : " without a gateway payment")
                                + ", but gateway payment " + gatewayPaymentId + " of " + paidAmount
                                + " also arrived. Refund required.",
                        orderId, customerPhone));
            } else {
                log.info("Gateway payment {} for already-confirmed order {} — no action", gatewayPaymentId, orderId);
            }
            return;
        }
        if (!AWAITING_PAYMENT.contains(order.getStatus())) {
            // Money for an order that is no longer live (usually cutoff-cancelled while the link stayed
            // payable). Never auto-confirm it — a human must refund or revive it onto a valid bake day.
            payment.setStatus(PaymentStatus.REVIEW_REQUIRED);
            paymentRepository.save(payment);
            effects.accept(() -> alertService.raise("PAYMENT_FOR_INACTIVE_ORDER",
                    "Order " + orderId + " is " + order.getStatus() + " but gateway payment " + gatewayPaymentId
                            + " of " + paidAmount + " arrived. Refund or revive manually.",
                    orderId, customerPhone));
            return;
        }

        // Fail closed: an amount we cannot verify is held for review, never auto-confirmed.
        if (expected == null || paidAmount == null || paidAmount.compareTo(expected) != 0) {
            payment.setStatus(PaymentStatus.REVIEW_REQUIRED);
            paymentRepository.save(payment);
            order.setStatus(OrderStatus.PAYMENT_REVIEW_REQUIRED);
            orderRepository.save(order);
            effects.accept(() -> alertService.raise("PAYMENT_AMOUNT_MISMATCH",
                    "Order " + orderId + ": paid " + paidAmount + " but expected " + expected
                            + " (gateway payment " + gatewayPaymentId + "). Held for review.",
                    orderId, customerPhone));
            return;
        }

        payment.setStatus(PaymentStatus.GATEWAY_CAPTURED);
        if (payment.getAmount() == null) payment.setAmount(paidAmount);
        // Flush before confirmIfNotConfirmed, which clears the persistence context.
        paymentRepository.saveAndFlush(payment);

        int flipped = orderRepository.confirmIfNotConfirmed(orderId, OrderStatus.CONFIRMED);
        if (flipped == 1) {
            orderRepository.findById(orderId).ifPresent(confirmed -> {
                consumeCredit(confirmed);
                paymentConfirmationEffects(confirmed, effects);
            });
        }
        log.info("Gateway payment {} confirmed order {}", gatewayPaymentId, orderId);
    }

    private record WebBinding(String id, String reference, String link, String state) { }

    private void confirmWebPayment(Order order, String paymentId, String linkId, BigDecimal amount,
                                   String attemptId, String reference, String currency, boolean webhook,
                                   Consumer<Runnable> effects) {
        Payment payment = paymentRepository.findByOrder(order).orElse(null);
        if (payment == null) {
            effects.accept(() -> alertService.raise("PAYMENT_WEB_BINDING", "WEB order has no accepted payment",
                    order.getId(), null));
            return;
        }
        // Capacity is always acquired first. The attempt row serializes early binding with dispatch persistence.
        List<WebBinding> bindings = jdbcTemplate.query("""
            select id,provider_reference,provider_link_id,state
            from web_checkout_attempts where order_id=? and customer_id=? for update
            """, (rs, row) -> new WebBinding(rs.getString("id"), rs.getString("provider_reference"),
                rs.getString("provider_link_id"), rs.getString("state")),
            order.getId(), order.getCustomer().getId());
        WebBinding binding = bindings.size() == 1 ? bindings.get(0) : null;
        // PostgreSQL now() is transaction-start time. Recheck wall-clock time AFTER all lock waits.
        boolean live = binding != null && Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "select expires_at > clock_timestamp() from web_checkout_attempts where id=?",
            Boolean.class, binding.id()));
        boolean settled = payment.getStatus() == PaymentStatus.GATEWAY_CAPTURED;
        boolean samePayment = paymentId != null && paymentId.equals(payment.getGatewayPaymentId());
        boolean active = AWAITING_PAYMENT.contains(order.getStatus())
            || (settled && (order.getStatus() == OrderStatus.CONFIRMED
                || order.getStatus() == OrderStatus.IN_BAKING
                || order.getStatus() == OrderStatus.OUT_FOR_DELIVERY
                || order.getStatus() == OrderStatus.COMPLETED));
        boolean validMoney = amount != null && amount.signum() > 0 && amount.scale() <= 2
            && payment.getAmount() != null && amount.compareTo(payment.getAmount()) == 0
            && order.getTotalAmount() != null && amount.compareTo(order.getTotalAmount()) == 0;
        boolean validIds = paymentId != null && paymentId.matches("pay_[A-Za-z0-9_]{1,96}")
            && linkId != null && linkId.matches("plink_[A-Za-z0-9_]{1,94}");
        boolean stateAllows = binding != null && Set.of("CREATING", "UNKNOWN", "PAYABLE").contains(binding.state());
        boolean signedBinding = binding != null && binding.id().equals(attemptId)
            && binding.reference() != null && binding.reference().equals(reference) && "INR".equals(currency);
        boolean savedLink = binding != null && linkId != null && linkId.equals(binding.link())
            && linkId.equals(payment.getGatewayLinkId());
        boolean canBind = webhook && signedBinding && binding != null
            && Set.of("CREATING", "UNKNOWN").contains(binding.state())
            && binding.link() == null && payment.getGatewayLinkId() == null;
        boolean conflictingPayment = payment.getGatewayPaymentId() != null && !samePayment;
        if (binding == null || !stateAllows || !"RAZORPAY".equals(payment.getProvider())
            || (!live && !(settled && samePayment && savedLink))
            || !active || order.isCutoffCancelled() || !validMoney || !validIds || conflictingPayment
            || (webhook ? !signedBinding : !savedLink) || (!savedLink && !canBind)
            || (order.getCreditApplied() != null && order.getCreditApplied().signum() != 0)) {
            holdWebPayment(order, payment,
                    "WEB payment failed attempt, deadline, amount, currency or provider binding checks", effects);
            return;
        }
        if (canBind) {
            Boolean used = jdbcTemplate.queryForObject(
                    "select exists(select 1 from web_checkout_attempts where provider_link_id=? and id<>?)",
                    Boolean.class, linkId, binding.id());
            if (Boolean.TRUE.equals(used)) {
                holdWebPayment(order, payment, "Gateway link is already bound to another WEB attempt", effects);
                return;
            }
            jdbcTemplate.update("""
                update web_checkout_attempts set provider_link_id=?,updated_at=now()
                where id=? and provider_link_id is null
                """, linkId, binding.id());
            payment.setGatewayLinkId(linkId);
        }
        if (settled && samePayment) return;
        payment.setGatewayPaymentId(paymentId);
        payment.setStatus(PaymentStatus.GATEWAY_CAPTURED);
        // Empty the paid basket, but keep any edits the customer made after checkout.
        jdbcTemplate.update("""
                update web_carts set version=version+1, lines='[]'::jsonb, delivery_date=null, updated_at=now()
                where customer_id=? and version=(select cart_version from web_checkout_attempts where id=?)
                """, order.getCustomer().getId(), binding.id());
        paymentsCaptured(order, payment, effects);
    }

    private void paymentsCaptured(Order order, @NonNull Payment payment, Consumer<Runnable> effects) {
        paymentRepository.saveAndFlush(payment);
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        paymentConfirmationEffects(order, effects);
    }

    private void paymentConfirmationEffects(Order order, Consumer<Runnable> effects) {
        // Load the customer while attached; cutoff executes these effects with no persistence context.
        if (order.getCustomer() != null) org.hibernate.Hibernate.initialize(order.getCustomer());
        effects.accept(() -> notifyPaymentConfirmed(order));
        effects.accept(() -> notifyAdminOrderPaid(order));
    }

    private void notifyPaymentConfirmed(Order order) {
        TransactionTemplate receiptScope = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        receiptScope.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        receiptScope.executeWithoutResult(status -> {
            // An empty transaction scope provides synchronization, not a database transaction.
            // Let Spring own both the holder and its synchronization: repository transactions
            // can then suspend/resume it safely, and cleanup cannot orphan a synchronization
            // before the subsequent REQUIRES_NEW admin alert. This also works without HTTP OSIV.
            Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(
                    Objects.requireNonNull(entityManagerFactory)));
            customerNotifier.orderConfirmed(order);
        });
    }

    private void holdWebPayment(Order order, Payment payment, String reason) {
        holdWebPayment(order, payment, reason, effect -> effect.run());
    }

    private void holdWebPayment(Order order, Payment payment, String reason, Consumer<Runnable> effects) {
        payment.setStatus(PaymentStatus.REVIEW_REQUIRED);
        paymentRepository.save(payment);
        if (AWAITING_PAYMENT.contains(order.getStatus())) {
            order.setStatus(OrderStatus.PAYMENT_REVIEW_REQUIRED);
            orderRepository.save(order);
        }
        String phone = order.getCustomer() != null ? order.getCustomer().getPhone() : null;
        effects.accept(() -> alertService.raise("PAYMENT_WEB_BINDING", reason + " (order " + order.getId() + ")",
                order.getId(), phone));
    }

    // Gateway payments auto-confirm with no screenshot to review, so ping the admin that a paid order landed.
    private void notifyAdminOrderPaid(Order order) {
        String ref = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
        Customer c = order.getCustomer();
        String name = c != null && c.getName() != null ? c.getName() : (c != null ? c.getPhone() : "customer");
        String phone = c != null ? c.getPhone() : null;
        String amt = order.getTotalAmount() != null
                ? " \u00b7 \u20b9" + order.getTotalAmount().setScale(0, RoundingMode.DOWN) : "";
        String date = order.getDeliveryDate() != null ? order.getDeliveryDate().format(ADMIN_DATE_FMT) : "date TBD";
        alertService.raise("ORDER_PAID",
                "\u2705 New paid order \u2014 confirmed & ready to bake\n\n" +
                "Order: *" + ref + "* \u00b7 " + date + amt + "\n" +
                "Customer: " + name + (phone != null ? " (" + phone + ")" : ""),
                order.getId(), phone);
    }

    // Deduct the credit this order used from the customer's running balance (once, on first confirm).
    private void consumeCredit(Order order) {
        if (order.getChannel() == OrderChannel.WEB) return;
        java.math.BigDecimal applied = order.getCreditApplied();
        if (applied == null || applied.signum() <= 0) return;
        Customer c = order.getCustomer();
        if (c == null) return;
        java.math.BigDecimal bal = c.getCreditBalance() == null ? java.math.BigDecimal.ZERO : c.getCreditBalance();
        java.math.BigDecimal newBal = bal.subtract(applied);
        if (newBal.signum() < 0) newBal = java.math.BigDecimal.ZERO;
        c.setCreditBalance(newBal);
        customerRepository.save(c);
        log.info("Consumed ₹{} credit from customer {} for order {} (balance now ₹{})",
                applied, c.getId(), order.getId(), newBal);
    }

    @Transactional
    public void flagForReview(@NonNull Long orderId) {
        capacityLock.acquire();
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.PAYMENT_REVIEW_REQUIRED);
            orderRepository.save(order);
            log.info("Admin flagged order {} for payment review", orderId);
        });
    }

    @Transactional
    public void sendMessage(String phone, String message) {
        Customer customer = customerRepository.findByPhone(phone).orElse(null);
        String orderRef = null;
        if (customer != null) {
            orderRef = orderRepository.findAllByCustomerIdOrderByCreatedAtDesc(customer.getId()).stream()
                    .findFirst()
                    .map(o -> o.getOrderNumber() != null ? o.getOrderNumber() : "#" + o.getId())
                    .orElse(null);
        }
        customerNotifier.customerUpdate(customer, phone, message, orderRef);
        if (customer != null) {
            AdminMessage msg = new AdminMessage();
            msg.setCustomer(customer);
            msg.setDirection(AdminMessage.Direction.OUTBOUND);
            msg.setMessage(message);
            adminMessageRepository.save(msg);
        }
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

    @Transactional
    public void updateOrderDeliveryDate(@NonNull Long orderId, LocalDate deliveryDate) {
        capacityLock.acquire();
        orderRepository.findById(orderId).ifPresent(order -> {
            // Accepted WEB quotes include the bake date. This ordinary admin form cannot re-quote,
            // and its controller does not catch service exceptions: reject without mutating or throwing.
            if (order.getChannel() == OrderChannel.WEB) {
                log.warn("Rejected ordinary delivery-date edit for immutable WEB order {}", orderId);
                return;
            }
            LocalDate previous = order.getDeliveryDate();
            order.setDeliveryDate(deliveryDate);
            orderRepository.save(order);
            log.info("Admin set delivery date {} on order {}", deliveryDate, orderId);
            // Price is intentionally left unchanged. Notify the customer only on a real change.
            if (!deliveryDate.equals(previous)) {
                customerNotifier.orderDeliveryDateChanged(order);
            }
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
    public void markInBaking(@NonNull Long orderId) {
        capacityLock.acquire();
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.IN_BAKING);
            orderRepository.save(order);
            if (order.isNotifyCustomer()) customerNotifier.orderInBaking(order);
            log.info("Admin marked order {} as IN_BAKING", orderId);
        });
    }

    @Transactional
    public void markOutForDelivery(@NonNull Long orderId) {
        capacityLock.acquire();
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.OUT_FOR_DELIVERY);
            orderRepository.save(order);
            if (order.isNotifyCustomer()) customerNotifier.orderOutForDelivery(order);
            log.info("Admin marked order {} as OUT_FOR_DELIVERY", orderId);
        });
    }

    @Transactional
    public void markCompleted(@NonNull Long orderId) {
        capacityLock.acquire();
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);
            if (order.isNotifyCustomer()) customerNotifier.orderDelivered(order);
            log.info("Admin marked order {} as COMPLETED", orderId);
        });
    }

    @Transactional
    public void cancelOrder(@NonNull Long orderId) {
        capacityLock.acquire();
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            if (order.isNotifyCustomer()) customerNotifier.orderCancelled(order, null);
            log.info("Admin cancelled order {}", orderId);
        });
    }

    /** Flip whether status changes on this order send WhatsApp updates to the customer. Returns the new state. */
    @Transactional
    public boolean toggleNotify(@NonNull Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return false;
        order.setNotifyCustomer(!order.isNotifyCustomer());
        orderRepository.save(order);
        log.info("Admin set notifyCustomer={} on order {}", order.isNotifyCustomer(), orderId);
        return order.isNotifyCustomer();
    }

    public byte[] getQrImage(@NonNull Long orderId) {
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
        List<Long> customerIds = customers.stream().map(c -> c.getId()).toList();
        return loadViews(orderRepository.findAllByCustomerIdInOrderByCreatedAtDesc(customerIds));
    }

    @Transactional(readOnly = true)
    public ConversationThread getConversation(String phone, OrderStatus status, LocalDate from, LocalDate to) {
        Customer customer = customerRepository.findByPhone(phone).orElse(null);
        if (customer == null) return null;
        List<AdminMessage> messages = adminMessageRepository.findAllByCustomerIdOrderByCreatedAtAsc(customer.getId());
        List<AdminOrderView> orders = loadViews(orderRepository.findAll(
                buildCustomerOrderFilterSpec(customer.getId(), status, from, to), orderSort()));
        return new ConversationThread(customer, messages, orders);
    }

    @Transactional(readOnly = true)
    public OrderPage findOrders(LocalDate from, LocalDate to, OrderStatus status,
                                String customer, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, orderSort());
        Page<Order> result = orderRepository.findAll(buildOrderFilterSpec(from, to, status, customer), pageable);

        return new OrderPage(loadViews(result.getContent()),
                result.getNumber(), result.getTotalPages(), result.getTotalElements(), size);
    }

    @Transactional(readOnly = true)
    public String exportOrdersCsv(LocalDate from, LocalDate to, OrderStatus status, String customer) {
        List<Order> orders = orderRepository.findAll(buildOrderFilterSpec(from, to, status, customer), orderSort());
        List<AdminOrderView> views = loadViews(orders);

        StringJoiner csv = new StringJoiner("\n");
        csv.add("Order Number,Order Id,Phone,Customer Name,Status,Delivery Date,Created At,Total Amount,Items,Delivery Address");

        for (AdminOrderView v : views) {
            Order o = v.order();
            csv.add(String.join(",",
                    csvCell(o.getOrderNumber() != null ? o.getOrderNumber() : ""),
                    csvCell(o.getId() != null ? o.getId().toString() : ""),
                    csvCell(o.getCustomer() != null ? o.getCustomer().getPhone() : ""),
                    csvCell(o.getCustomer() != null ? o.getCustomer().getName() : ""),
                    csvCell(o.getStatus() != null ? o.getStatus().name() : ""),
                    csvCell(o.getDeliveryDate() != null ? o.getDeliveryDate().toString() : ""),
                    csvCell(o.getCreatedAt() != null ? o.getCreatedAt().toString() : ""),
                    csvCell(o.getTotalAmount() != null ? o.getTotalAmount().toPlainString() : ""),
                    csvCell(v.itemsSummary()),
                    csvCell(o.getDeliveryAddress())
            ));
        }

        return csv.toString() + "\n";
    }

    private Specification<Order> buildOrderFilterSpec(LocalDate from, LocalDate to, OrderStatus status, String customer) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (from != null) preds.add(cb.greaterThanOrEqualTo(root.get("deliveryDate"), from));
            if (to != null) preds.add(cb.lessThanOrEqualTo(root.get("deliveryDate"), to));
            if (status != null) preds.add(cb.equal(root.get("status"), status));
            if (customer != null && !customer.isBlank()) {
                String like = "%" + customer.trim().toLowerCase() + "%";
                var cust = root.join("customer");
                preds.add(cb.or(
                        cb.like(cb.lower(cust.get("phone")), like),
                        cb.like(cb.lower(cust.get("name")), like)));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    private Specification<Order> buildCustomerOrderFilterSpec(Long customerId, OrderStatus status,
                                                              LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("customer").get("id"), customerId));
            if (status != null) preds.add(cb.equal(root.get("status"), status));
            if (from != null) preds.add(cb.greaterThanOrEqualTo(root.get("deliveryDate"), from));
            if (to != null) preds.add(cb.lessThanOrEqualTo(root.get("deliveryDate"), to));
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    private @NonNull Sort orderSort() {
        return Sort.by(Sort.Order.desc("deliveryDate"), Sort.Order.desc("createdAt"));
    }

    private String csvCell(String raw) {
        if (raw == null) return "\"\"";
        String escaped = raw.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
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
                .sorted(Comparator.comparingInt((BakeListItem item) -> item.totalQuantity()).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public BakeSheet buildBakeSheet(LocalDate date) {
        List<Order> orders = orderRepository.findAllByStatusInAndDeliveryDateOrderByDeliveryDateAsc(
                Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING), date);
        List<BakeListItem> aggregate = buildBakeList(date);
        int totalItems = aggregate.stream().mapToInt(item -> item.totalQuantity()).sum();
        return new BakeSheet(date, aggregate, loadViews(orders), totalItems);
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
