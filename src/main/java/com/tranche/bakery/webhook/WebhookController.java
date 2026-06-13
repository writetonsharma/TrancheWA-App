package com.tranche.bakery.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    @Value("${whatsapp.webhook.verify-token}")
    private String verifyToken;

    private final WebhookHandler webhookHandler;

    // Meta webhook verification handshake
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("Webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }
        log.warn("Webhook verification failed: mode={}, token mismatch={}", mode, !verifyToken.equals(token));
        return ResponseEntity.status(403).body("Forbidden");
    }

    // Receive incoming messages from Meta
    @PostMapping
    public ResponseEntity<String> receive(@RequestBody JsonNode payload) {
        log.debug("Webhook payload received: {}", payload);
        try {
            webhookHandler.handle(payload);
        } catch (Exception e) {
            log.error("Error handling webhook payload", e);
        }
        // Always return 200 to Meta, even on errors, to avoid retries
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
