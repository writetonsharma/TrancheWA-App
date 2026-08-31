package com.tranche.bakery.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Unit tests for the F&F flat-price precedence: item → category → all-items → list price. */
class CustomerPricingTest {

    private Customer customer() {
        Customer c = new Customer();
        c.setPhone("919000000001");
        return c;
    }

    @Test
    void noOverride_returnsNull() {
        Customer c = customer();
        assertThat(c.unitPriceFor("Chocolate Babka Buns", "Sweet Bakes")).isNull();
    }

    @Test
    void allItemsOverride_appliesToEverything() {
        Customer c = customer();
        c.setPricingOverride(new BigDecimal("150"));
        assertThat(c.unitPriceFor("Chocolate Babka Buns", "Sweet Bakes")).isEqualByComparingTo("150");
        assertThat(c.unitPriceFor("Classic Table White", "Loaves")).isEqualByComparingTo("150");
    }

    @Test
    void categoryPrice_winsOverAllItems() {
        Customer c = customer();
        c.setPricingOverride(new BigDecimal("150"));
        c.getCategoryPrices().put("Sweet Bakes", new BigDecimal("300"));
        assertThat(c.unitPriceFor("Chocolate Babka Buns", "Sweet Bakes")).isEqualByComparingTo("300");
        // A category with no entry falls back to the all-items override.
        assertThat(c.unitPriceFor("Classic Table White", "Loaves")).isEqualByComparingTo("150");
    }

    @Test
    void itemPrice_winsOverCategoryAndAllItems() {
        Customer c = customer();
        c.setPricingOverride(new BigDecimal("150"));
        c.getCategoryPrices().put("Sweet Bakes", new BigDecimal("300"));
        c.getItemPrices().put("Chocolate Babka Buns", new BigDecimal("500"));
        assertThat(c.unitPriceFor("Chocolate Babka Buns", "Sweet Bakes")).isEqualByComparingTo("500");
        // A sibling item with no override still uses the category price.
        assertThat(c.unitPriceFor("Cinnamon Rolls", "Sweet Bakes")).isEqualByComparingTo("300");
    }

    @Test
    void itemPriceAlone_makesCustomerFriendsAndFamily() {
        Customer c = customer();
        c.getItemPrices().put("Chocolate Babka Buns", new BigDecimal("500"));
        assertThat(c.hasActiveOverride()).isTrue();
        assertThat(c.unitPriceFor("Chocolate Babka Buns", "Sweet Bakes")).isEqualByComparingTo("500");
        // No category or all-items price, so other items stay at list price.
        assertThat(c.unitPriceFor("Cinnamon Rolls", "Sweet Bakes")).isNull();
    }
}
