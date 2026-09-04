package com.tranche.bakery.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

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
}
