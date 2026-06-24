package com.tranche.bakery.customer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByPhone(String phone);
    List<Customer> findByPhoneContainingOrNameContainingIgnoreCase(String phone, String name);

    @Query("SELECT c FROM Customer c WHERE c.pricingOverride IS NOT NULL ORDER BY c.overrideExpiresAt ASC NULLS LAST")
    List<Customer> findAllWithPricingOverride();
}
