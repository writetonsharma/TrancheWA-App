package com.tranche.bakery.payment;

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
            JsonNode notes = switch (event) {
                case "payment_link.paid" -> root.path("payload").path("payment_link").path("entity").path("notes");
                case "payment.captured" -> root.path("payload").path("payment").path("entity").path("notes");
                default -> null;
            };
            if (notes != null && !notes.isMissingNode()) {
                route(event, notes);
            }
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            // 200 so Razorpay doesn't retry forever on a bug on our side; alert instead.
            log.error("Razorpay webhook processing error: {}", e.getMessage(), e);
            alertService.raise("RAZORPAY_WEBHOOK", "Razorpay webhook failed: " + e.getMessage(), null, null);
            return ResponseEntity.ok("ok");
        }
    }

    private void route(String event, JsonNode notes) {
        String kind = notes.path("kind").asText("");
        if ("ORDER".equals(kind)) {
            long id = notes.path("orderId").asLong(0);
            if (id > 0) {
                adminService.approvePayment(id); // idempotent: first CONFIRMED only notifies/consumes credit
                log.info("Razorpay {} → confirmed order {}", event, id);
            }
        } else if ("SUBSCRIPTION".equals(kind)) {
            long id = notes.path("subscriptionId").asLong(0);
            if (id > 0) {
                subscriptionService.activate(id); // idempotent: activateIfPending only flips once
                log.info("Razorpay {} → activated subscription {}", event, id);
            }
        }
    }
}
