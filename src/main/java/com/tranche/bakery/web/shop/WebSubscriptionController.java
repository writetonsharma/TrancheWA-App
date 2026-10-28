package com.tranche.bakery.web.shop;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Website subscription sign-up; shares the checkout rollout gate. */
@RestController
@RequestMapping("/api/subscriptions")
public class WebSubscriptionController {
    private final WebSubscriptionService subscriptions;
    private final boolean enabled;

    public WebSubscriptionController(WebSubscriptionService subscriptions,
            @Value("${bakery.web.checkout.enabled:false}") boolean enabled) {
        this.subscriptions = subscriptions;
        this.enabled = enabled;
    }

    @GetMapping("/plans")
    public WebSubscriptionService.Plans plans(HttpServletRequest request) {
        requireEnabled();
        return subscriptions.plans(request);
    }

    @GetMapping("/current")
    public WebSubscriptionService.Current current(HttpServletRequest request) {
        requireEnabled();
        return subscriptions.current(request);
    }

    @PostMapping
    public WebSubscriptionService.Current create(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        requireEnabled();
        return subscriptions.create(body, request);
    }

    @PostMapping("/current/cancel")
    public WebSubscriptionService.Current cancel(HttpServletRequest request) {
        requireEnabled();
        return subscriptions.cancel(request);
    }

    private void requireEnabled() {
        if (!enabled) throw new StorefrontProblemException(HttpStatus.NOT_FOUND, "DISABLED", "Website subscriptions are unavailable.");
    }
}
