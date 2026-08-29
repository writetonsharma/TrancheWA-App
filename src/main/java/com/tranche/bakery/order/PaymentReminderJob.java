package com.tranche.bakery.order;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.alert.Alert;
import com.tranche.bakery.alert.AlertRepository;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.whatsapp.CustomerNotifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Nudges customers who placed an order but haven't shared a payment screenshot yet
 * (order still PENDING_CONFIRMATION). Two reminders, both free-form (customer ordered
 * recently, so within WhatsApp's 24h window):
 *  - Early nudge: ~1h after the QR was sent, ONLY for orders delivering 2+ days out.
 *  - Last-chance: 4:30 PM the day before delivery (30 min before the 5 PM cutoff),
 *    unless the order was placed within an hour of that cutoff.
 * Next-day orders therefore get only the 4:30 last-chance; day-after+ orders get both.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReminderJob {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final AlertRepository alertRepository;
    private final CustomerNotifier customerNotifier;

    @Value("${bakery.order.cutoff-hour}")
    private int cutoffHour;

    private static final String EARLY_TYPE = "PAYMENT_REMINDER_EARLY";
    private static final String LASTCHANCE_TYPE = "PAYMENT_REMINDER_LASTCHANCE";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");

    /** Early nudge — hourly at :30 (off the cutoff hour so it never races CutoffJob). */
    @Scheduled(cron = "0 30 * * * *", zone = "Asia/Kolkata")
    @Transactional
    public void earlyReminder() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        for (Order order : orderRepository.findAllByStatusOrderByCreatedAtDesc(OrderStatus.PENDING_CONFIRMATION)) {
            Payment payment = paymentRepository.findByOrder(order).orElse(null);
            if (payment == null || payment.getCreatedAt() == null) continue;
            if (payment.getCreatedAt().isAfter(oneHourAgo)) continue;               // QR sent less than 1h ago
            if (order.getDeliveryDate() == null) continue;
            LocalDate orderDay = payment.getCreatedAt().toLocalDate();
            if (!order.getDeliveryDate().isAfter(orderDay.plusDays(1))) continue;   // not 2+ days out (next-day skips this)
            if (alertRepository.existsByTypeAndOrderId(EARLY_TYPE, order.getId())) continue;
            sendReminder(order, earlyMessage(order, payment), EARLY_TYPE);
        }
    }

    /** Last-chance — 4:30 PM IST, for orders delivering tomorrow. */
    @Scheduled(cron = "0 30 16 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void lastChanceReminder() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDateTime cutoffMinusOneHour = LocalDate.now().atTime(cutoffHour - 1, 0);
        for (Order order : orderRepository.findAllByStatusAndDeliveryDateOrderByDeliveryDateAsc(
                OrderStatus.PENDING_CONFIRMATION, tomorrow)) {
            Payment payment = paymentRepository.findByOrder(order).orElse(null);
            if (payment == null || payment.getCreatedAt() == null) continue;
            if (!payment.getCreatedAt().isBefore(cutoffMinusOneHour)) continue;     // placed within 1h of cutoff — skip
            if (alertRepository.existsByTypeAndOrderId(LASTCHANCE_TYPE, order.getId())) continue;
            sendReminder(order, lastChanceMessage(order, payment), LASTCHANCE_TYPE);
        }
    }

    private void sendReminder(Order order, String message, String type) {
        String phone = order.getCustomer().getPhone();
        customerNotifier.paymentReminder(order, message);
        log.info("Sent {} for order {} to {}", type, ref(order), phone);
        // Record as a resolved (non-actionable) alert purely for one-shot dedup — no admin ping.
        Alert record = new Alert();
        record.setType(type);
        record.setMessage(type + " sent for order " + ref(order));
        record.setOrderId(order.getId());
        record.setCustomerPhone(phone);
        record.setResolved(true);
        record.setResolvedAt(LocalDateTime.now());
        alertRepository.save(record);
    }

    private String earlyMessage(Order order, Payment payment) {
        return "🥖 Almost there! Your order *" + ref(order) + "* isn't confirmed yet.\n\n" +
                "Please complete the UPI payment" + amountPart(payment) +
                " and *share the payment screenshot here* — that's the final step to lock in your bake.";
    }

    private String lastChanceMessage(Order order, Payment payment) {
        String deliveryPart = order.getDeliveryDate() != null
                ? " (delivery " + order.getDeliveryDate().format(DATE_FMT) + ")" : "";
        return "⏰ Last reminder for order *" + ref(order) + "*" + deliveryPart + ".\n\n" +
                "It's still awaiting payment. Please pay" + amountPart(payment) +
                " and *share the screenshot here before " + cutoffLabel() + "* to confirm it — " +
                "otherwise it'll be set aside for the day.";
    }

    private String ref(Order order) {
        return order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
    }

    private String amountPart(Payment payment) {
        return payment.getAmount() != null
                ? " of ₹" + payment.getAmount().setScale(0, RoundingMode.DOWN) : "";
    }

    private String cutoffLabel() {
        int h = cutoffHour % 12;
        if (h == 0) h = 12;
        return h + " " + (cutoffHour < 12 ? "AM" : "PM") + " today";
    }
}
