package com.tranche.bakery.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findTopByCustomerIdAndStatusOrderByCreatedAtDesc(Long customerId, OrderStatus status);

    List<Order> findAllByStatusOrderByCreatedAtDesc(OrderStatus status);

    List<Order> findAllByStatusIn(Collection<OrderStatus> statuses);

    List<Order> findAllByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
            OrderStatus status, LocalDateTime from, LocalDateTime to);

    List<Order> findAllByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            OrderStatus status, LocalDateTime before);

    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt BETWEEN :from AND :to ORDER BY o.createdAt ASC")
    List<Order> findConfirmedBetween(@Param("status") OrderStatus status,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);
}
