package com.tranche.bakery.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findAllByResolvedFalseOrderByCreatedAtDesc();

    boolean existsByTypeAndOrderIdAndResolvedFalse(String type, Long orderId);

    @Modifying
    @Query("UPDATE Alert a SET a.resolved = true, a.resolvedAt = CURRENT_TIMESTAMP WHERE a.resolved = false")
    void resolveAll();
}
