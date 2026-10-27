package com.tranche.bakery.web.shop;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-cookie-only website account; deliberately shares the checkout rollout gate. */
@RestController
@RequestMapping("/api/account")
public class WebAccountController {
    private final WebAccountService accounts;
    private final boolean enabled;

    public WebAccountController(WebAccountService accounts,
            @Value("${bakery.web.checkout.enabled:false}") boolean enabled) {
        this.accounts = accounts;
        this.enabled = enabled;
    }

    @GetMapping("/profile")
    public WebAccountService.Profile profile(HttpServletRequest request) {
        requireEnabled();
        return accounts.profile(request);
    }

    @PutMapping("/profile")
    public WebAccountService.Profile save(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        requireEnabled();
        return accounts.save(body, request);
    }

    @GetMapping("/orders")
    public WebAccountService.History orders(HttpServletRequest request) {
        requireEnabled();
        return accounts.orders(request);
    }

    @GetMapping("/orders/{id}")
    public WebAccountService.Detail order(@PathVariable String id, HttpServletRequest request) {
        requireEnabled();
        return accounts.order(id, request);
    }

    @GetMapping("/checkout")
    public WebAccountService.Recovery checkout(HttpServletRequest request) {
        requireEnabled();
        return accounts.checkout(request);
    }

    private void requireEnabled() {
        if (!enabled) throw new StorefrontProblemException(HttpStatus.NOT_FOUND, "DISABLED", "Website checkout is unavailable.");
    }
}