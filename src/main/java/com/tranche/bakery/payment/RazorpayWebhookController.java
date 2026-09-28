package com.tranche.bakery.payment;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.subscription.SubscriptionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Receives Razorpay webhooks and auto-confirms the matching order/subscription once payment lands.
 * Path is under /webhook/** so it's already permitAll + CSRF-exempt in SecurityConfig.
 */
@RestController
@RequestMapping("/webhook/razorpay")
@RequiredArgsConstructor
@Slf4j
public class RazorpayWebhookController {

    private final RazorpayService razorpayService;
    private final AdminService adminService;
    private final SubscriptionService subscriptionService;
    private final AlertService alertService;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping
    public ResponseEntity<String> handle(@RequestBody(required = false) String rawBody,
                                         @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!razorpayService.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Razorpay webhook: invalid or missing signature");
            return ResponseEntity.status(400).body("invalid signature");
        }
        try {
            JsonNode root = mapper.readTree(rawBody);
            String event = root.path("event").asText("");
            // A paid payment link emits BOTH payment_link.paid and payment.captured; act on the link
            // event only so the order/subscription is confirmed once (payment.captured is ignored here).
            // NOTE: this means a Razorpay Checkout (non-link) payment would never be picked up — that
            // path emits order.paid/payment.captured and needs handling added here before it is used.
            if ("payment_link.paid".equals(event)) {
                JsonNode linkEntity = root.path("payload").path("payment_link").path("entity");
                route(event, linkEntity.path("notes"),
                        root.path("payload").path("payment").path("entity").path("id").asText(null),
                        linkEntity.path("id").asText(null),
                        rupees(linkEntity.path("amount_paid")));
            } else if ("payment_link.partially_paid".equals(event)) {
                // We always create links with accept_partial=false, so this only happens if the link was
                // edited in the dashboard. It stays partially_paid and never emits payment_link.paid,
                // so without this alert the money would arrive completely unseen.
                JsonNode linkEntity = root.path("payload").path("payment_link").path("entity");
                alertService.raise("PAYMENT_PARTIAL",
                        "Link " + linkEntity.path("id").asText(null) + " took a partial payment of "
                                + rupees(linkEntity.path("amount_paid")) + " against "
                                + rupees(linkEntity.path("amount")) + ". Partial payments are never enabled "
                                + "by us — reconcile manually; no order will be confirmed.",
                        null, null);
            }
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            // 5xx so Razorpay retries: silently dropping a payment we already took is worse than a
            // retry storm. The alert surfaces it immediately either way.
            log.error("Razorpay webhook processing error: {}", e.getMessage(), e);
            alertService.raise("RAZORPAY_WEBHOOK", "Razorpay webhook failed: " + e.getMessage(), null, null);
            return ResponseEntity.status(500).body("processing failed");
        }
    }

    private void route(String event, JsonNode notes, String paymentId, String linkId, BigDecimal paidAmount) {
        String kind = notes.isMissingNode() || notes.isNull() ? "" : notes.path("kind").asText("");
        if ("ORDER".equals(kind)) {
            long id = notes.path("orderId").asLong(0);
            if (id > 0) {
                // Idempotent: first CONFIRMED only notifies/consumes credit.
                adminService.confirmGatewayPayment(id, paymentId, linkId, paidAmount);
                log.info("Razorpay {} → order {} (payment {})", event, id, paymentId);
                return;
            }
        } else if ("SUBSCRIPTION".equals(kind)) {
            long id = notes.path("subscriptionId").asLong(0);
            if (id > 0) {
                // Idempotent: activateIfPending only flips once.
                subscriptionService.activateFromGateway(id, paymentId, linkId, paidAmount);
                log.info("Razorpay {} → subscription {} (payment {})", event, id, paymentId);
                return;
            }
        }
        // Links created by hand in the Razorpay dashboard carry no notes, so money can arrive with
        // nothing to attach it to. Never let that pass silently.
        alertService.raise("PAYMENT_UNROUTABLE",
                "Gateway payment " + paymentId + " of " + paidAmount + " on link " + linkId
                        + " has no recognisable notes (kind='" + kind + "') — reconcile manually.",
                null, null);
    }

    /** Razorpay reports money in paise; null when absent or not a number, and callers never confirm on null. */
    private static BigDecimal rupees(JsonNode node) {
        return node.isNumber() ? BigDecimal.valueOf(node.asLong()).movePointLeft(2) : null;
    }
}
