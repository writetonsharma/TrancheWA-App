package com.tranche.bakery.customer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByPhone(String phone);
    List<Customer> findByPhoneContainingOrNameContainingIgnoreCase(String phone, String name);

    List<Customer> findAllByOrderByCreatedAtDesc();

    @Query("SELECT c FROM Customer c WHERE c.pricingOverride IS NOT NULL OR SIZE(c.categoryPrices) > 0 OR SIZE(c.itemPrices) > 0 OR c.subscriptionEligible = true ORDER BY c.overrideExpiresAt ASC NULLS LAST")
    List<Customer> findAllWithPricingOverride();

    // "Ghost" rows: a stray/spam number that messaged once and never progressed. Only rows with no
    // name, no pricing/F&F flag, no orders, subscriptions, admin messages or feedback, and dormant
    // since before the cutoff qualify — so a real (named) lead or an admin-added contact is never hit.
    @Query("""
            SELECT c FROM Customer c
            WHERE (c.name IS NULL OR TRIM(c.name) = '')
              AND c.pricingOverride IS NULL
              AND SIZE(c.categoryPrices) = 0
              AND SIZE(c.itemPrices) = 0
              AND c.subscriptionEligible = false
              AND c.createdAt < :cutoff
              AND (c.lastInboundAt IS NULL OR c.lastInboundAt < :cutoff)
              AND NOT EXISTS (SELECT o FROM Order o WHERE o.customer = c)
              AND NOT EXISTS (SELECT s FROM Subscription s WHERE s.customer = c)
              AND NOT EXISTS (SELECT m FROM AdminMessage m WHERE m.customer = c)
              AND NOT EXISTS (SELECT f FROM Feedback f WHERE f.customer = c)
            """)
    List<Customer> findStaleGhosts(@Param("cutoff") LocalDateTime cutoff);
}
