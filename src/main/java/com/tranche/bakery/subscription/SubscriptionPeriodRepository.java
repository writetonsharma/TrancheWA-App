package com.tranche.bakery.subscription;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPeriodRepository extends JpaRepository<SubscriptionPeriod, Long> {

    Optional<SubscriptionPeriod> findBySubscriptionIdAndWeekNumber(Long subscriptionId, int weekNumber);

    List<SubscriptionPeriod> findAllBySubscriptionId(Long subscriptionId);
}
