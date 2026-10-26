package com.tranche.bakery.flow.actions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentLinkRevocations;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.payment.PaymentTestMode;
import com.tranche.bakery.payment.QrCodeService;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

@Component
@RequiredArgsConstructor
@Slf4j
public class SendPaymentQrAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final QrCodeService qrCodeService;
    private final WhatsAppClient whatsAppClient;
    private final AlertService alertService;
    private final PaymentTestMode paymentTestMode;
    private final RazorpayService razorpayService;
    private final PlatformTransactionManager transactionManager;
    private final CapacityLock capacityLock;
    private final JdbcTemplate jdbc;
    private final PaymentLinkRevocations revocations;

    @PersistenceContext private EntityManager entityManager;

    @Value("${bakery.payment.upi-id}")
    private String upiId;

    @Value("${bakery.payment.upi-name}")
    private String upiName;

    @Value("${bakery.support.whatsapp}")
    private String supportPhone;

    @Value("${bakery.order.cutoff-hour}")
    private int cutoffHour;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private String supportLine() {
        return "Trouble paying? Message or call us on " + supportPhone + ".";
    }

    /** An unpaid link for the same amount is still good — re-send it rather than mint a second one. */
    private String reusableLinkUrl(Payment payment, BigDecimal amount) {
        return payment.getStatus() == PaymentStatus.PENDING
                && "RAZORPAY".equals(payment.getProvider())
                && payment.getGatewayLinkUrl() != null
                && payment.getAmount() != null
                && payment.getAmount().compareTo(amount) == 0
                        ? payment.getGatewayLinkUrl()
                        : null;
    }

    /** CutoffJob cancels the order at the cutoff the evening before its bake day, so the link dies then too. */
    private Instant cutoffInstantFor(Order order) {
        LocalDate deliveryDate = order.getDeliveryDate();
        return deliveryDate == null ? null
                : deliveryDate.minusDays(1).atTime(cutoffHour, 0).atZone(IST).toInstant();
    }

    @Override
    public String getName() { return "SEND_PAYMENT_QR"; }

    @Override
    public void execute(ActionContext ctx) {
        // Keep ownership redirects in the atomic flow transition. Everything that can call
        // a provider runs only after the reservation and PAYMENT_PENDING state are durable.
        String orderId = ctx.contextValue("orderId");
        if (orderId != null) {
            Order order = orderRepository.findById(Long.parseLong(orderId)).orElse(null);
            if (order != null && (order.getChannel() == com.tranche.bakery.order.OrderChannel.WEB
                    || !order.getCustomer().getId().equals(ctx.getCustomer().getId()))) {
                ctx.setRedirectState("IDLE");
                return;
            }
        }
        ctx.afterCommit(() -> {
            try {
                dispatch(ctx, orderId);
            } catch (Exception ex) {
                // Match FlowEngine's action-failure handling, without ever rolling back a
                // committed reservation or losing a link already saved before the send.
                log.error("Payment dispatch failed for order {}: {}", orderId, ex.getMessage(), ex);
            }
        });
    }

    private void dispatch(ActionContext ctx, String orderIdStr) {
        if (orderIdStr == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "We couldn't find your order. Send *hi* to return to the main menu.");
            return;
        }

        // Customer is initialized explicitly; no lazy reads may depend on the flow transaction.
        Order order = orderRepository.findWithCustomerById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "We couldn't find your order. Send *hi* to return to the main menu.");
            return;
        }

        // OSIV may keep the flow's entity managed after commit. Freeze this dispatch's
        // snapshot so a later transaction cannot mutate its amount/status by identity.
        if (entityManager.contains(order)) entityManager.detach(order);

        // WEB payment belongs exclusively to its durable checkout attempt, never to this legacy route.
        if (order.getChannel() == com.tranche.bakery.order.OrderChannel.WEB
                || !order.getCustomer().getId().equals(ctx.getCustomer().getId())) {
            return;
        }

        // Only send a payment prompt while the order is still awaiting payment. Guards against a stale
        // button (e.g. the review-message "Cancel") re-entering PAYMENT_PENDING after the Razorpay webhook
        // already confirmed the order — without this it would re-issue a payment link/QR for a paid order.
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            String ref = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
            whatsAppClient.sendText(ctx.getCustomer().getPhone(), order.getStatus() == OrderStatus.CANCELLED
                    ? "Order " + ref + " was cancelled. Send *hi* to place a new order. \uD83E\uDD56"
                    : "\u2705 Order " + ref + " is already confirmed \u2014 no further payment is needed. Send *hi* anytime. \uD83E\uDD56");
            return;
        }

        // A payment prompt is also an explicit retry point after an earlier revoke failed.
        revocations.retryForOrder(order.getId());
        BigDecimal amount = paymentTestMode.amountFor(ctx.getCustomer().getPhone(), order.getTotalAmount());
        if (amount.signum() <= 0) {
            // Account credit fully covers this order — there's nothing to charge. Route it to admin
            // review so the existing Approve action confirms it and consumes the credit.
            Boolean covered = withPayableOrder(order, current -> {
                current.setStatus(OrderStatus.PAYMENT_REVIEW_REQUIRED);
                orderRepository.save(current);
                return true;
            });
            if (!Boolean.TRUE.equals(covered)) return;
            String ref0 = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "✅ *Order " + ref0 + " — fully covered by your account credit!*\n\n" +
                    "No payment needed. We'll confirm your order shortly. 🥖");
            alertService.raise("CREDIT_COVERED",
                    "Order " + order.getId() + " is fully covered by customer credit — approve to confirm.",
                    order.getId(), ctx.getCustomer().getPhone());
            return;
        }
        String orderRef = order.getOrderNumber() != null ? order.getOrderNumber() : String.valueOf(order.getId());
        // UPI/WhatsApp note accepts letters, numbers and spaces only — turn any other char into a space.
        String note = ("Tranche Bakery Order " + orderRef).replaceAll("[^A-Za-z0-9 ]", " ").replaceAll(" +", " ").trim();

        // Gateway path: a hosted payment link, auto-confirmed by the webhook. There is deliberately no
        // fall back to the QR flow — two live payment routes for one order invites a double payment.
        if (razorpayService.isGatewayMode()) {
            // Commit dispatch ownership BEFORE any provider call. The durable fence survives
            // another prompt, another application instance, timeout, or process restart.
            GatewayDispatch dispatch = claimGatewayDispatch(order, amount);
            if (dispatch == null) return;
            String linkUrl = dispatch.reusableUrl();
            if (linkUrl == null) {
                try {
                    // Never open a second route if revoking a superseded route is ambiguous.
                    // All network calls run after the claim transaction has released its locks.
                    if (dispatch.previousLinkId() != null) {
                        razorpayService.cancelPaymentLink(dispatch.previousLinkId());
                    }
                    RazorpayService.PaymentLink link = razorpayService.createPaymentLink(
                            amount, note, dispatch.reference(),
                            ctx.getCustomer().getName(), ctx.getCustomer().getPhone(),
                            java.util.Map.of("kind", "ORDER", "orderId", String.valueOf(order.getId())),
                            cutoffInstantFor(order));

                    // A cancellation, webhook or cart edit can commit during the provider call.
                    // Recheck under the shared mutex, but NEVER retain it across provider I/O.
                    // Commit the payment before WhatsApp exposes its link; a send failure/retry
                    // must reuse this durable row, not create another payment route.
                    StoredLink stored;
                    try {
                        stored = storeGatewayLink(order, dispatch, link, amount);
                    } catch (RuntimeException ex) {
                        cancelUnusedLink(link.id(), order.getId());
                        throw ex;
                    }
                    if (stored == null || !stored.createdLinkRetained()) {
                        boolean revoked = cancelUnusedLink(link.id(), order.getId());
                        finishGatewayDispatch(order.getId(), dispatch.reference(), revoked ? "COMPLETE" : "UNKNOWN");
                    }
                    if (stored == null) return;
                    linkUrl = stored.url();
                    log.info("Created Razorpay payment link {} for order {}", link.id(), order.getId());
                } catch (Exception e) {
                    // A failed call is not evidence that Razorpay did not create the link.
                    // If even this update fails, committed CREATING still blocks redispatch.
                    finishGatewayDispatch(order.getId(), dispatch.reference(), "UNKNOWN");
                    log.error("Razorpay link failed for order {}: {}", order.getId(), e.getMessage());
                    alertService.raise("PAYMENT_LINK_FAILED",
                            "Could not create a Razorpay link for order " + order.getId() + ": " + e.getMessage(),
                            order.getId(), ctx.getCustomer().getPhone());
                    whatsAppClient.sendText(ctx.getCustomer().getPhone(), String.format(
                            "We couldn't generate the payment link for order %s just now.%n%n%s",
                            orderRef, supportLine()));
                    return;
                }
            }

            whatsAppClient.sendText(ctx.getCustomer().getPhone(), String.format(
                    "*Order %s \u2014 \u20b9%.2f*%s%n%n\uD83D\uDC49 Tap to pay securely (UPI, card or netbanking):%n%s%n%n"
                            + "Your order confirms automatically the moment payment is received. \uD83E\uDD56%n%n%s",
                    orderRef, amount, savingsLine(order), linkUrl, supportLine()));
            try {
                whatsAppClient.sendButtons(ctx.getCustomer().getPhone(),
                        "Need to cancel this order? You can do so below.",
                        List.of(new WhatsAppMessage.Button("cancel_" + order.getId(), "Cancel Order")));
            } catch (Exception ignore) { /* best effort */ }
            return;
        }

        log.info("Sending payment QR for order {} amount {}", order.getId(), amount);

        // A failed persistence phase must not fall through to an unrecorded manual payment.
        // QR generation/upload failures can fall back only AFTER the amount is committed.
        if (!Boolean.TRUE.equals(storeQrPayment(order, amount, null))) return;
        try {
            byte[] qrPng = qrCodeService.generateUpiQrPng(upiId, upiName, amount, note);
            log.info("QR PNG generated, {} bytes", qrPng.length);
            if (qrPng.length == 0) throw new IllegalStateException("QR PNG is empty — AWT rendering failed");

            Boolean saved = storeQrPayment(order, amount, qrPng);
            if (!Boolean.TRUE.equals(saved)) return;

            String mediaId = whatsAppClient.uploadMedia(qrPng, "payment-qr.png");
            log.info("Media uploaded, mediaId={}", mediaId);
            String caption = String.format(
                    "*Order %s — ₹%.2f*%s%n%nScan the QR code above with any UPI app, or pay manually to *%s*.%n%n📸 *Important — after paying, share the payment screenshot here.* That's the final step to confirm your order.",
                    order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId(),
                    amount, savingsLine(order), upiId);
            whatsAppClient.sendImage(ctx.getCustomer().getPhone(), mediaId, caption);
            log.info("sendImage called for order {}", order.getId());
        } catch (Exception e) {
            log.error("Payment QR flow failed for order {}: {}", order.getId(), e.getMessage(), e);
            Boolean payable = withPayableOrder(order, current -> paymentRepository.findByOrder(current)
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING
                    && payment.getAmount() != null && payment.getAmount().compareTo(amount) == 0)
                .isPresent());
            if (!Boolean.TRUE.equals(payable)) return;
            alertService.raise("QR_FAILURE",
                    "Payment QR failed for order " + order.getId() + ": " + e.getMessage(),
                    order.getId(), ctx.getCustomer().getPhone());
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    String.format("*Order %s — please complete payment of ₹%.2f.*%n%n" +
                            "*UPI ID:* %s%n%n📸 *Important — after paying, share the payment screenshot here.* That's the final step to confirm your order.",
                            order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId(),
                            amount, upiId));
        }

        try {
            whatsAppClient.sendButtons(ctx.getCustomer().getPhone(),
                    "Need to cancel this order? You can do so below.",
                    List.of(new WhatsAppMessage.Button("cancel_" + order.getId(), "Cancel Order")));
        } catch (Exception e) {
            log.error("Failed to send cancel button for order {}: {}", order.getId(), e.getMessage());
        }
    }

    private record StoredLink(String url, boolean createdLinkRetained) { }

    private record GatewayDispatch(String reference, String previousLinkId, String reusableUrl) { }

    private GatewayDispatch claimGatewayDispatch(Order snapshot, BigDecimal amount) {
        return withPayableOrder(snapshot, current -> {
            // The shared mutex serializes initial insertion as well as concurrent edits and
            // settlement. It is held only for this short persistence phase, never for I/O.
            var states = jdbc.queryForList(
                    "SELECT state FROM whatsapp_payment_dispatches WHERE order_id = ?", String.class, current.getId());
            if (!states.isEmpty() && !"COMPLETE".equals(states.get(0))) return null;
            Payment payment = paymentRepository.findByOrder(current).orElse(null);
            if (payment != null) {
                entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
                if (payment.getStatus() != PaymentStatus.PENDING) return null;
                String existing = reusableLinkUrl(payment, amount);
                if (existing != null) return new GatewayDispatch(null, payment.getGatewayLinkId(), existing);
            }
            String reference = "wa-" + UUID.randomUUID().toString().replace("-", "");
            int claimed = jdbc.update("""
                    INSERT INTO whatsapp_payment_dispatches(order_id, provider_reference, state)
                    VALUES (?, ?, 'CREATING')
                    ON CONFLICT (order_id) DO UPDATE SET provider_reference = EXCLUDED.provider_reference,
                        state = 'CREATING', updated_at = now()
                    WHERE whatsapp_payment_dispatches.state = 'COMPLETE'
                    """, current.getId(), reference);
            return claimed == 1 ? new GatewayDispatch(reference,
                    payment == null ? null : payment.getGatewayLinkId(), null) : null;
        });
    }

    /** Own transaction must finish before any alert, WhatsApp, or revocation callback. */
    private void finishGatewayDispatch(Long orderId, String reference, String state) {
        TransactionTemplate transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE whatsapp_payment_dispatches SET state = ?, updated_at = now()
                WHERE order_id = ? AND provider_reference = ? AND state = 'CREATING'
                """, state, orderId, reference));
    }

    private Boolean storeQrPayment(Order snapshot, BigDecimal amount, byte[] qrPng) {
        return withPayableOrder(snapshot, current -> {
            Payment payment = paymentRepository.findByOrder(current).orElseGet(() -> {
                Payment p = new Payment();
                p.setOrder(current);
                p.setUpiId(upiId);
                return p;
            });
            if (payment.getId() != null) entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
            if (payment.getStatus() != PaymentStatus.PENDING) return false;
            payment.setAmount(amount);
            if (qrPng != null) payment.setQrImageData(qrPng);
            paymentRepository.save(payment);
            return true;
        });
    }

    private StoredLink storeGatewayLink(Order snapshot, GatewayDispatch dispatch,
                                        RazorpayService.PaymentLink link, BigDecimal amount) {
        String expectedLinkId = dispatch.previousLinkId();
        return withPayableOrder(snapshot, current -> {
            Payment payment = paymentRepository.findByOrder(current).orElseGet(() -> {
                Payment p = new Payment();
                p.setOrder(current);
                return p;
            });
            if (payment.getId() != null) entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
            if (payment.getStatus() != PaymentStatus.PENDING) return null;
            String existing = reusableLinkUrl(payment, amount);
            if (existing != null && !link.id().equals(payment.getGatewayLinkId())) {
                return new StoredLink(existing, false);
            }
            if (existing == null) {
                // Do not overwrite another dispatch's payment or a concurrent link revocation.
                if (!Objects.equals(expectedLinkId, payment.getGatewayLinkId())) return null;
                payment.setProvider("RAZORPAY");
                payment.setGatewayLinkId(link.id());
                payment.setGatewayLinkUrl(link.shortUrl());
                payment.setAmount(amount);
                paymentRepository.save(payment);
            }
            // Link and completed claim commit together; a failed commit leaves CREATING,
            // not permission for a retry to send a second provider request.
            jdbc.update("""
                    UPDATE whatsapp_payment_dispatches SET state = 'COMPLETE', updated_at = now()
                    WHERE order_id = ? AND provider_reference = ? AND state = 'CREATING'
                    """, current.getId(), dispatch.reference());
            return new StoredLink(existing == null ? link.shortUrl() : existing, true);
        });
    }

    /** Short persistence-only phase: no network and no stale entity merges after dispatch. */
    private <T> T withPayableOrder(Order snapshot, Function<Order, T> work) {
        var channel = snapshot.getChannel();
        Long customerId = snapshot.getCustomer().getId();
        BigDecimal total = snapshot.getTotalAmount();
        TransactionTemplate transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> {
            capacityLock.acquire();
            Order current = orderRepository.findWithCustomerById(snapshot.getId()).orElse(null);
            if (current == null) return null;
            // HTTP may have an OSIV persistence context: a query alone can return its stale entity.
            entityManager.refresh(current);
            if (current.getChannel() != channel
                    || !current.getCustomer().getId().equals(customerId)
                    || current.getStatus() != OrderStatus.PENDING_CONFIRMATION
                    || current.getTotalAmount().compareTo(total) != 0) return null;
                if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM payment_link_revocations WHERE order_id = ?)",
                    Boolean.class, current.getId()))) return null;
            return work.apply(current);
        });
    }

    private boolean cancelUnusedLink(String linkId, Long orderId) {
        try {
            razorpayService.cancelPaymentLink(linkId);
            return true;
        } catch (Exception ex) {
            log.error("Could not revoke unused link {} for order {}: {}", linkId, orderId, ex.getMessage());
            alertService.raise("PAYMENT_LINK_FAILED",
                    "Unused payment link " + linkId + " for order " + orderId + " needs revocation.",
                    orderId, null);
                    return false;
        }
    }

    // "💰 You're saving ₹X on this order!" — surfaced on the payment prompt to encourage completion.
    private String savingsLine(Order order) {
        BigDecimal disc = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal batch = order.getBatchDiscountAmount() != null ? order.getBatchDiscountAmount() : BigDecimal.ZERO;
        BigDecimal savings = disc.add(batch);
        return savings.signum() > 0
                ? String.format("%n\uD83D\uDCB0 *You're saving \u20B9%.0f on this order!*", savings)
                : "";
    }
}
