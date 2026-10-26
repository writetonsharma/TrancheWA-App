package com.tranche.bakery.web.shop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class StorefrontController {
    private final StorefrontCatalogService catalogService;
    private final StorefrontCustomerContext customerContext;

    @Value("${bakery.web.enabled:false}")
    private boolean enabled;

    @GetMapping("/api/catalog")
    public ResponseEntity<StorefrontDtos.Catalog> catalog() {
        requireEnabled();
        // Always public list prices, even for an authenticated browser.
        return noStore(catalogService.catalog());
    }

    @PostMapping("/api/quote")
    public ResponseEntity<StorefrontDtos.Quote> quote(@RequestBody(required = false) JsonNode body,
                                                     HttpServletRequest request) {
        requireEnabled();
        return noStore(catalogService.quote(body, customerContext.currentCustomer(request)));
    }

    private void requireEnabled() {
        if (!enabled) throw new StorefrontProblemException(HttpStatus.NOT_FOUND,
                "DISABLED", "Website ordering preview is unavailable.");
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }
}