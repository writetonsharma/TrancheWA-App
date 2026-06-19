package com.tranche.bakery.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AdminMessageRepository extends JpaRepository<AdminMessage, Long> {
    List<AdminMessage> findAllByCustomerIdOrderByCreatedAtAsc(Long customerId);
}
