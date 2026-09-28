package com.tranche.bakery.order;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
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
    private final OrderService orderService;
    private final AdminService adminService;
    private final AlertService alertService;
    private final WhatsAppClient whatsAppClient;
    private final CustomerNotifier customerNotifier;

    private static final String CUTOFF_MESSAGE =
            "⏰ A gentle note — it's 5 PM and your order is still incomplete, so it has been set aside for today.\n\n" +
            "Whenever you're ready, simply send *hi* to start fresh. We'd love to bake for you! 🥖";

    @Scheduled(cron = "0 0 ${bakery.order.cutoff-hour} * * *", zone = "Asia/Kolkata")
    @Transactional
    public void cancelUnfinishedOrders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // All unconfirmed drafts — always cancel at cutoff
        List<Order> drafts = orderRepository.findAllByStatusIn(List.of(OrderStatus.DRAFT));

        // Confirmed but unpaid — only cancel if delivery date is tomorrow or earlier (cutoff passed)
        List<Order> dueForCancel = orderRepository.findAllByStatusIn(
                List.of(OrderStatus.PENDING_CONFIRMATION)).stream()
                .filter(o -> o.getDeliveryDate() == null || !o.getDeliveryDate().isAfter(tomorrow))
                .toList();

        // Settle before destroying: an order whose payment webhook never arrived looks unpaid here, and
        // cancelling it would leave us holding the customer's money with no order. Drops out of the list.
        List<Order> pendingPayment = new ArrayList<>();
        for (Order order : dueForCancel) {
            if (!settledAtGateway(order)) pendingPayment.add(order);
        }

        List<Order> expiredOrders = new ArrayList<>();
        expiredOrders.addAll(drafts);
        expiredOrders.addAll(pendingPayment);

        // Mark confirmed-but-unpaid cancellations so a late payment against the old QR can revive them.
        pendingPayment.forEach(o -> o.setCutoffCancelled(true));

        if (expiredOrders.isEmpty()) {
            log.info("Cutoff job: no unfinished orders to cancel.");
            return;
        }

        log.info("Cutoff job: cancelling {} unfinished orders.", expiredOrders.size());

        for (Order order : expiredOrders) {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            orderService.revokePaymentLink(order);

            conversationRepository
                    .findTopByCustomerOrderByStartedAtDesc(order.getCustomer())
                    .ifPresent(conv -> {
                        conv.setState("IDLE");
                        conv.setContext(null);
                        conversationRepository.save(conv);
                    });
        }

        // Drafts were mid-flow (recent activity) → free-form is in-window.
        for (Order order : drafts) {
            try {
                whatsAppClient.sendText(order.getCustomer().getPhone(), CUTOFF_MESSAGE);
            } catch (Exception e) {
                log.warn("Cutoff job: failed to notify draft customer {} — {}",
                        order.getCustomer().getPhone(), e.getMessage());
            }
        }

        // Unpaid confirmed orders may be advance orders (last message >24h ago) → template fallback.
        for (Order order : pendingPayment) {
            customerNotifier.orderCancelled(order, "Payment was not received before the daily cut-off.");
        }
    }

    /**
     * Asks Razorpay what really happened to this order's link before we cancel it. Returns true when the
     * money is already at the gateway, in which case the order must not be cancelled.
     */
    private boolean settledAtGateway(Order order) {
        if (!razorpayService.isConfigured()) return false;
        String linkId = paymentRepository.findByOrder(order).map(Payment::getGatewayLinkId).orElse(null);
        if (linkId == null) return false;

        RazorpayService.LinkState state;
        try {
            state = razorpayService.fetchPaymentLink(linkId);
        } catch (Exception e) {
            // Unreachable gateway must not confirm anything. Cancelling is the safe default: the status
            // guard in confirmGatewayPayment then holds any late payment for review instead of dropping it.
            log.warn("Cutoff job: could not check payment link {} for order {} — {}",
                    linkId, order.getId(), e.getMessage());
            return false;
        }

        if ("paid".equals(state.status())) {
            log.warn("Cutoff job: link {} for order {} is paid but no webhook arrived — confirming now",
                    linkId, order.getId());
            adminService.confirmGatewayPayment(order.getId(), state.paymentId(), linkId, state.amountPaid());
            return true;
        }
        if ("partially_paid".equals(state.status())) {
            order.setStatus(OrderStatus.PAYMENT_REVIEW_REQUIRED);
            orderRepository.save(order);
            alertService.raise("PAYMENT_PARTIAL",
                    "Link " + linkId + " for order " + order.getId() + " took a partial payment of "
                            + state.amountPaid() + " and reached the cutoff. Held for review, not cancelled.",
                    order.getId(), order.getCustomer().getPhone());
            return true;
        }
        return false;
    }
}
