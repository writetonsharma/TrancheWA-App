package com.tranche.bakery.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class FriendsFamilyPricingTest {

    @Test
    void loadsPresetFromJson() throws Exception {
        FriendsFamilyPricing preset = new FriendsFamilyPricing(new ObjectMapper());

        assertThat(preset.categoryPrices())
                .containsEntry("Loaves", new BigDecimal("190"))
                .containsEntry("Buns & Rolls", new BigDecimal("180"));
        assertThat(preset.itemPrices())
                .containsEntry("Multi-Seed Loaf", new BigDecimal("210"))
                .containsEntry("Chocolate Babka Buns", new BigDecimal("350"))
                .containsEntry("Olive, Tomato & Rosemary Focaccia", new BigDecimal("260"));
        assertThat(preset.subscriptionEligible()).isTrue();
        assertThat(preset.size()).isGreaterThanOrEqualTo(20);
    }
}
