package com.tranche.bakery.subscription;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findAllByStatus(SubscriptionStatus status);

    List<Subscription> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    boolean existsByCustomerIdAndStatus(Long customerId, SubscriptionStatus status);

    List<Subscription> findAllByOrderByCreatedAtDesc();
}
