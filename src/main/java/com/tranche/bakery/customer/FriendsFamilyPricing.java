package com.tranche.bakery.customer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Friends &amp; Family flat-price preset, loaded from friends-family-pricing.json at boot.
 * The admin Pricing page stamps this onto a customer with one click, so the rate card lives
 * in config (edit the JSON to retune for everyone) rather than being re-typed per customer.
 */
@Component
@Slf4j
public class FriendsFamilyPricing {

    private final Map<String, BigDecimal> categoryPrices;
    private final Map<String, BigDecimal> itemPrices;
    private final boolean subscriptionEligible;

    public FriendsFamilyPricing(ObjectMapper objectMapper) throws IOException {
        var resource = new ClassPathResource("friends-family-pricing.json");
        Preset preset = objectMapper.readValue(resource.getInputStream(), Preset.class);
        this.categoryPrices = preset.categoryPrices != null ? preset.categoryPrices : new LinkedHashMap<>();
        this.itemPrices = preset.itemPrices != null ? preset.itemPrices : new LinkedHashMap<>();
        this.subscriptionEligible = preset.subscriptionEligible;
        log.info("Loaded F&F pricing preset: {} category + {} item price(s)",
                categoryPrices.size(), itemPrices.size());
    }

    public Map<String, BigDecimal> categoryPrices() { return new LinkedHashMap<>(categoryPrices); }

    public Map<String, BigDecimal> itemPrices() { return new LinkedHashMap<>(itemPrices); }

    public boolean subscriptionEligible() { return subscriptionEligible; }

    public int size() { return categoryPrices.size() + itemPrices.size(); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Preset {
        public Map<String, BigDecimal> categoryPrices;
        public Map<String, BigDecimal> itemPrices;
        public boolean subscriptionEligible = true;
    }
}
