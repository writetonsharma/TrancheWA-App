package com.tranche.bakery.order;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findAllByOrderId(Long orderId);

    @Query("select (count(oi) > 0) from OrderItem oi "
            + "where oi.order.id = :orderId and oi.menuItem.category.name = :categoryName")
    boolean existsByOrderIdAndCategoryName(@Param("orderId") Long orderId,
                                           @Param("categoryName") String categoryName);

    @Query("select coalesce(sum(oi.quantity), 0) from OrderItem oi "
            + "where oi.order.deliveryDate = :date and oi.order.status in :statuses")
    long sumBookedQuantity(@Param("date") LocalDate date,
                           @Param("statuses") Collection<OrderStatus> statuses);
}
