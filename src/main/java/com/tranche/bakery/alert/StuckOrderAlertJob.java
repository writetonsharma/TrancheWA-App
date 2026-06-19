package com.tranche.bakery.alert;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
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
    private final AlertRepository alertRepository;
    private final WhatsAppClient whatsAppClient;

    private static final String REMINDER_MESSAGE =
            "👋 Hi! It looks like you started an order but haven't finished yet.\n\n" +
            "If you'd like to continue, just send *hi* and pick up where you left off.\n\n" +
            "_Orders that aren't completed by 6 PM will be set aside for the day._";

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")
    public void checkStuckDrafts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<Order> stuckDrafts = orderRepository.findAllByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                OrderStatus.DRAFT, cutoff);

        for (Order order : stuckDrafts) {
            if (alertRepository.existsByTypeAndOrderIdAndResolvedFalse("DRAFT_REMINDER", order.getId())) {
                continue;
            }
            String orderRef = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
            try {
                whatsAppClient.sendText(order.getCustomer().getPhone(), REMINDER_MESSAGE);
                log.info("Sent draft reminder for order {} to {}", orderRef, order.getCustomer().getPhone());
            } catch (Exception e) {
                log.warn("Failed to send draft reminder for order {}: {}", orderRef, e.getMessage());
            }
            alertService.raise("DRAFT_REMINDER",
                    "Reminder sent for stuck draft " + orderRef,
                    order.getId(),
                    order.getCustomer() != null ? order.getCustomer().getPhone() : null);
        }
    }
}
