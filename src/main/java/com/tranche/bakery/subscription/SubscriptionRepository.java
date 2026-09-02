package com.tranche.bakery.subscription;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findAllByStatus(SubscriptionStatus status);

    List<Subscription> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    boolean existsByCustomerIdAndStatus(Long customerId, SubscriptionStatus status);

    List<Subscription> findAllByOrderByCreatedAtDesc();

    // Atomic, race-safe activation: flips exactly one PENDING_PAYMENT row to ACTIVE (concurrent/duplicate
    // approvals see 0 rows affected, so the confirmation is sent only once). Returns rows updated.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.status = :active, s.startDate = :start, s.endDate = :end "
            + "WHERE s.id = :id AND s.status = :pending")
    int activateIfPending(@Param("id") Long id,
                          @Param("start") LocalDate start,
                          @Param("end") LocalDate end,
                          @Param("active") SubscriptionStatus active,
                          @Param("pending") SubscriptionStatus pending);
}
