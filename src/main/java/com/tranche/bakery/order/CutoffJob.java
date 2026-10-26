package com.tranche.bakery.order;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentLinkRevocations;
import com.tranche.bakery.payment.PostTransactionEffects;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.whatsapp.CustomerNotifier;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class CutoffJob {

    private final OrderRepository orderRepository;
    private final ConversationRepository conversationRepository;
    private final PaymentRepository paymentRepository;
    private final RazorpayService razorpayService;
    private final AdminService adminService;
    private final AlertService alertService;
    private final WhatsAppClient whatsAppClient;
    private final CustomerNotifier customerNotifier;
    private final CapacityLock capacityLock;
    private final PlatformTransactionManager transactionManager;
    private final PaymentLinkRevocations revocations;

    private static final String CUTOFF_MESSAGE =
            "⏰ A gentle note — it's 5 PM and your order is still incomplete, so it has been set aside for today.\n\n" +
            "Whenever you're ready, simply send *hi* to start fresh. We'd love to bake for you! 🥖";

    @Scheduled(cron = "0 0 ${bakery.order.cutoff-hour} * * *", zone = "Asia/Kolkata")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cancelUnfinishedOrders() {
        PostTransactionEffects.run(this::cancelOutsideTransaction);
    }

    private void cancelOutsideTransaction() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        TransactionTemplate transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate snapshot = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setReadOnly(true);
        List<Candidate> candidates = snapshot.execute(status -> orderRepository.findAllByStatusIn(
                List.of(OrderStatus.DRAFT, OrderStatus.PENDING_CONFIRMATION)).stream()
                .filter(order -> due(order, tomorrow))
                .map(order -> {
                    Payment payment = paymentRepository.findByOrder(order).orElse(null);
                    return new Candidate(Objects.requireNonNull(order.getId()), order.getStatus(),
                            payment == null ? null : payment.getId(),
                            payment == null ? null : payment.getGatewayLinkId(),
                            payment == null ? null : payment.getStatus());
                }).toList());
        if (candidates == null || candidates.isEmpty()) {
            log.info("Cutoff job: no unfinished orders to cancel.");
            return;
        }

        for (Candidate candidate : candidates) {
            // No database transaction (nor capacity mutex) survives either provider call.
            RazorpayService.LinkState state = fetch(candidate);
            Decision decision = transaction.execute(status -> decide(candidate, state, tomorrow));
            if (decision == null) continue;
            if (decision.revokeLink() != null) revocations.retryForOrder(candidate.orderId());
            // execute() has committed and released the capacity mutex. Failed sends cannot roll back
            // settlement/cancellation and must not prevent the remaining orders from being processed.
            for (Runnable effect : decision.effects()) {
                try {
                    effect.run();
                } catch (Exception e) {
                    log.warn("Cutoff job: post-commit notification failed for order {} — {}",
                            candidate.orderId(), e.getMessage());
                }
            }
        }
    }

    private record Candidate(Long orderId, OrderStatus status, Long paymentId, String linkId,
                             PaymentStatus paymentStatus) { }

    private record Decision(String revokeLink, List<Runnable> effects) { }

    private boolean due(Order order, LocalDate tomorrow) {
        return order.getStatus() == OrderStatus.DRAFT
                || (order.getStatus() == OrderStatus.PENDING_CONFIRMATION
                    && (order.getDeliveryDate() == null || !order.getDeliveryDate().isAfter(tomorrow)));
    }

    private RazorpayService.LinkState fetch(Candidate candidate) {
        if (candidate.status() == OrderStatus.DRAFT || candidate.linkId() == null
                || !razorpayService.isConfigured()) return null;
        try {
            return razorpayService.fetchPaymentLink(candidate.linkId());
        } catch (Exception e) {
            log.warn("Cutoff job: could not check payment link {} for order {} — {}",
                    candidate.linkId(), candidate.orderId(), e.getMessage());
            // Preserve the existing fail-closed policy: cancel, and hold any late payment for review.
            return null;
        }
    }

    private Decision decide(Candidate candidate, RazorpayService.LinkState state, LocalDate tomorrow) {
        capacityLock.acquire(); // capacity first; all entities are freshly loaded in this short transaction
        Order order = orderRepository.findById(Objects.requireNonNull(candidate.orderId())).orElse(null);
        if (order == null || order.getStatus() != candidate.status() || !due(order, tomorrow)) return null;
        Payment payment = paymentRepository.findByOrder(order).orElse(null);
        if (!samePayment(candidate, payment)) {
            log.info("Cutoff job: payment binding changed for order {}; leaving it for a fresh pass",
                    candidate.orderId());
            return null;
        }
        if (payment != null && (payment.getStatus() == PaymentStatus.GATEWAY_CAPTURED
                || payment.getStatus() == PaymentStatus.SCREENSHOT_VERIFIED
                || payment.getStatus() == PaymentStatus.CONFIRMED
                || payment.getStatus() == PaymentStatus.REVIEW_REQUIRED)) return null;

        if (state != null && "paid".equals(state.status())) {
            // Pass the captured link, never a newly re-read/replaced one. This uses the same WEB
            // attempt binding and wall-clock expiry guard as the existing trusted cutoff overload.
            return new Decision(null, adminService.confirmGatewayPaymentAtCutoff(Objects.requireNonNull(candidate.orderId()),
                    state.paymentId(), candidate.linkId(), state.amountPaid()));
        }
        if (state != null && "partially_paid".equals(state.status())) {
            order.setStatus(OrderStatus.PAYMENT_REVIEW_REQUIRED);
            orderRepository.save(order);
            String phone = order.getCustomer().getPhone();
            return new Decision(null, List.of(() -> alertService.raise("PAYMENT_PARTIAL",
                    "Link " + candidate.linkId() + " for order " + order.getId() + " took a partial payment of "
                            + state.amountPaid() + " and reached the cutoff. Held for review, not cancelled.",
                    order.getId(), phone)));
        }

        order.setStatus(OrderStatus.CANCELLED);
        if (candidate.status() == OrderStatus.PENDING_CONFIRMATION) order.setCutoffCancelled(true);
        orderRepository.save(order);
        List<Runnable> effects = new ArrayList<>();
        if (order.getChannel() != OrderChannel.WEB) {
            String phone = order.getCustomer().getPhone(); // initialize before detaching
            conversationRepository.findTopByCustomerOrderByStartedAtDesc(order.getCustomer()).ifPresent(conv -> {
                conv.setState("IDLE");
                conv.setContext(null);
                conversationRepository.save(conv);
            });
            if (candidate.status() == OrderStatus.DRAFT) {
                effects.add(() -> whatsAppClient.sendText(phone, CUTOFF_MESSAGE));
            } else {
                effects.add(() -> customerNotifier.orderCancelled(order,
                        "Payment was not received before the daily cut-off."));
            }
        }
        String revokeLink = payment != null && payment.getStatus() == PaymentStatus.PENDING
                ? payment.getGatewayLinkId() : null;
        if (revokeLink != null) revocations.fence(payment);
        return new Decision(revokeLink, effects);
    }

    private boolean samePayment(Candidate candidate, Payment payment) {
        return payment == null ? candidate.paymentId() == null
                : Objects.equals(candidate.paymentId(), payment.getId())
                    && Objects.equals(candidate.linkId(), payment.getGatewayLinkId())
                    && candidate.paymentStatus() == payment.getStatus();
    }

}
