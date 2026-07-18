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

    @Query("select coalesce(sum(oi.quantity), 0) from OrderItem oi where oi.order.id = :orderId")
    int sumQuantityByOrderId(@Param("orderId") Long orderId);

    @Query("select coalesce(sum(oi.quantity), 0) from OrderItem oi "
            + "where oi.menuItem.id = :itemId and oi.order.deliveryDate = :date "
            + "and oi.order.status in :statuses")
    long sumBookedQuantityForItem(@Param("itemId") Long itemId,
                                  @Param("date") LocalDate date,
                                  @Param("statuses") Collection<OrderStatus> statuses);

    @Query("select oi.menuItem.id, oi.order.deliveryDate, sum(oi.quantity) from OrderItem oi "
            + "where oi.order.deliveryDate in :dates and oi.order.status in :statuses "
            + "group by oi.menuItem.id, oi.order.deliveryDate")
    List<Object[]> sumBookedByItemAndDate(@Param("dates") Collection<LocalDate> dates,
                                          @Param("statuses") Collection<OrderStatus> statuses);
}
