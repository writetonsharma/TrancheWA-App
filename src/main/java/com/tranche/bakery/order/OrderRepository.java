package com.tranche.bakery.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findTopByCustomerIdAndStatusOrderByCreatedAtDesc(Long customerId, OrderStatus status);

    Optional<Order> findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(Long customerId, Collection<OrderStatus> statuses);

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

    List<Order> findAllByStatusAndDeliveryDateOrderByDeliveryDateAsc(OrderStatus status, java.time.LocalDate deliveryDate);

    List<Order> findAllByStatusInAndDeliveryDateOrderByDeliveryDateAsc(Collection<OrderStatus> statuses, java.time.LocalDate deliveryDate);

    List<Order> findAllByCustomerIdAndStatus(Long customerId, OrderStatus status);

    Optional<Order> findTopByCustomerIdAndStatusAndDeliveryDate(Long customerId, OrderStatus status, java.time.LocalDate deliveryDate);

    List<Order> findAllByCustomerIdAndStatusAndCutoffCancelledTrueOrderByUpdatedAtDesc(Long customerId, OrderStatus status);

    List<Order> findAllByStatusInAndDeliveryDateBetweenOrderByDeliveryDateAsc(Collection<OrderStatus> statuses, java.time.LocalDate from, java.time.LocalDate to);

    List<Order> findAllByStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(Collection<OrderStatus> statuses, LocalDateTime after);

    List<Order> findAllByCustomerIdInOrderByCreatedAtDesc(Collection<Long> customerIds);

    List<Order> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Order> findAllByStatusInAndDeliveryDateIsNullOrderByCreatedAtDesc(Collection<OrderStatus> statuses);

    long countByCustomerIdAndStatus(Long customerId, OrderStatus status);
}
