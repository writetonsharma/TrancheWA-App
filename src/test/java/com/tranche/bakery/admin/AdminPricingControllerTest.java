package com.tranche.bakery.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.math.BigDecimal;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;

@SpringBootTest
@ActiveProfiles("test")
class AdminPricingControllerTest {

    @Autowired AdminPricingController controller;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE customers RESTART IDENTITY CASCADE");
    }

    @Test
    void applyPreset_createsAndNormalizesNewCustomer() {
        var ra = new RedirectAttributesModelMap();
        controller.applyPreset("98118 43373", "Ravi", false, null, null, ra);

        Customer c = customerRepository.findByPhone("919811843373").orElseThrow();
        assertThat(c.getName()).isEqualTo("Ravi");
        assertThat(c.isSubscriptionEligible()).isTrue();
        assertThat(c.hasActiveOverride()).isTrue();
        assertThat(ra.getFlashAttributes().get("flash").toString()).contains("Added Ravi");
    }

    @Test
    void applyPreset_newCustomerWithoutName_isRejected() {
        var ra = new RedirectAttributesModelMap();
        controller.applyPreset("9811843399", null, false, null, null, ra);

        assertThat(customerRepository.findByPhone("919811843399")).isEmpty();
        assertThat(ra.getFlashAttributes().get("error")).isNotNull();
    }

    @Test
    void syncPreset_addsMissingRateCardPrices_keepsCustomPrices() {
        // An existing itemised F&F customer with a custom Cinnamon price and no tea cakes yet.
        Customer c = new Customer();
        c.setPhone("919820000001");
        c.setName("Old FnF");
        c.getCategoryPrices().put("Loaves", new BigDecimal("190"));
        c.getItemPrices().put("Cinnamon Rolls", new BigDecimal("250")); // custom (rate card says 280)
        customerRepository.save(c);

        controller.syncPreset(new RedirectAttributesModelMap());

        Customer reloaded = customerRepository.findByPhone("919820000001").orElseThrow();
        assertThat(reloaded.getItemPrices().get("Lemon Tea Cake")).isEqualByComparingTo("280");
        assertThat(reloaded.getItemPrices().get("Coffee & Walnut Tea Cake")).isEqualByComparingTo("330");
        assertThat(reloaded.getItemPrices().get("Dark Chocolate Marble Tea Cake")).isEqualByComparingTo("300");
        assertThat(reloaded.getItemPrices().get("Cinnamon Rolls")).isEqualByComparingTo("250"); // custom kept
    }

    @Test
    void syncPreset_skipsAllItemsFlatCustomers() {
        Customer c = new Customer();
        c.setPhone("919820000002");
        c.setName("Flat FnF");
        c.setPricingOverride(new BigDecimal("200")); // all-items flat rate, empty maps
        customerRepository.save(c);

        controller.syncPreset(new RedirectAttributesModelMap());

        Customer reloaded = customerRepository.findByPhone("919820000002").orElseThrow();
        assertThat(reloaded.getItemPrices()).isEmpty(); // their flat already covers new products
    }
}
