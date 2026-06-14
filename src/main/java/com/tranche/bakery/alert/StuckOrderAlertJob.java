package com.tranche.bakery.alert;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class StuckOrderAlertJob {

    private final OrderRepository orderRepository;
    private final AlertService alertService;

    // Runs every hour — alerts on orders awaiting payment screenshot for > 2 hours
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")
    public void checkStuckOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<Order> stuck = orderRepository.findAllByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                OrderStatus.PENDING_CONFIRMATION, cutoff);

        for (Order order : stuck) {
            String orderRef = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
            alertService.raise("STUCK_ORDER",
                    "Order " + orderRef + " has been waiting for payment screenshot for over 2 hours.",
                    order.getId(),
                    order.getCustomer() != null ? order.getCustomer().getPhone() : null);
            log.warn("Stuck order alert raised for {}", orderRef);
        }
    }
}
