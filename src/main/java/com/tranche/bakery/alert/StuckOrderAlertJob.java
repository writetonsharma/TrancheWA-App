package com.tranche.bakery.alert;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class StuckOrderAlertJob {

    private final OrderRepository orderRepository;
    private final AlertRepository alertRepository;
    private final WhatsAppClient whatsAppClient;

    private static final String REMINDER_MESSAGE =
            "👋 Hi! It looks like you started an order but haven't finished yet.\n\n" +
            "To order, send *hi* to clear this draft and start again.\n\n" +
            "_Orders that aren't completed by 5 PM will be set aside for the day._";

    // Disabled by default (cron "-"): customers now get only the two payment reminders (PaymentReminderJob).
    // Set BAKERY_DRAFT_REMINDER_CRON (e.g. "0 0 * * * *") to re-enable the stuck-draft nudge.
    @Scheduled(cron = "${bakery.draft-reminder.cron:-}", zone = "Asia/Kolkata")
    @Transactional
    public void checkStuckDrafts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<Order> stuckDrafts = orderRepository.findAllByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                OrderStatus.DRAFT, cutoff);

        for (Order order : stuckDrafts) {
            // One-shot: once a draft has been reminded, never remind again — even after the admin
            // resolves the dashboard alert. (ResolvedFalse used to let a resolved alert re-trigger this.)
            if (alertRepository.existsByTypeAndOrderId("DRAFT_REMINDER", order.getId())) {
                continue;
            }
            String orderRef = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
            String phone = order.getCustomer() != null ? order.getCustomer().getPhone() : null;
            try {
                if (phone != null) whatsAppClient.sendText(phone, REMINDER_MESSAGE);
                log.info("Sent draft reminder for order {} to {}", orderRef, phone);
            } catch (Exception e) {
                log.warn("Failed to send draft reminder for order {}: {}", orderRef, e.getMessage());
            }
            // Record as a resolved (non-actionable) alert purely for one-shot dedup — no admin ping.
            Alert record = new Alert();
            record.setType("DRAFT_REMINDER");
            record.setMessage("Reminder sent for stuck draft " + orderRef);
            record.setOrderId(order.getId());
            record.setCustomerPhone(phone);
            record.setResolved(true);
            record.setResolvedAt(LocalDateTime.now());
            alertRepository.save(record);
        }
    }
}
