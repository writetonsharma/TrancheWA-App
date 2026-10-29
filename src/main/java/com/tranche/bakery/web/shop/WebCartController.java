package com.tranche.bakery.web.shop;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Durable baskets and independently gated, customer-owned retail checkout. */
@RestController
public class WebCartController {
    private final WebCartService carts;
    private final WebCheckoutService checkouts;
    private final boolean enabled;

    public WebCartController(WebCartService carts, WebCheckoutService checkouts,
            @Value("${bakery.web.checkout.enabled:false}") boolean enabled) {
        this.carts = carts; this.checkouts = checkouts; this.enabled = enabled;
    }

    @GetMapping("/api/cart")
    public WebCartService.Cart get(HttpServletRequest request) {
        requireEnabled();
        return carts.get(request);
    }

    @PutMapping("/api/cart")
    public WebCartService.Cart put(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        requireEnabled();
        return carts.put(body, request);
    }

    @PostMapping("/api/checkout")
    public WebCheckoutService.Checkout checkout(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        requireEnabled();
        return checkouts.checkout(body, request);
    }

    @PostMapping("/api/checkout/prepare")
    public WebCheckoutService.Prepared prepare(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        requireEnabled();
        return checkouts.prepare(body, request);
    }

    @GetMapping("/api/checkout/{opaqueId}")
    public WebCheckoutService.Checkout status(@PathVariable String opaqueId, HttpServletRequest request) {
        requireEnabled();
        return checkouts.status(opaqueId, request);
    }

    @PostMapping("/api/checkout/{opaqueId}/cancel")
    public WebCheckoutService.Checkout cancel(@PathVariable String opaqueId, HttpServletRequest request) {
        requireEnabled();
        return checkouts.cancel(opaqueId, request);
    }

    private void requireEnabled() {
        if (!enabled) throw new StorefrontProblemException(HttpStatus.NOT_FOUND, "DISABLED", "Website checkout is unavailable.");
    }
}