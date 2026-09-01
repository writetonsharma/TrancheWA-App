package com.tranche.bakery;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.PaymentReminderJob;

class PaymentReminderJobTest extends FlowScenarioBase {

    @Autowired private PaymentReminderJob paymentReminderJob;
    @Autowired private OrderRepository orders;

    // Test profile sets cutoff-hour=23, so "within 1h of cutoff" means placed at/after 22:00.

    private void setDeliveryDate(Long orderId, LocalDate date) {
        jdbcTemplate.update("UPDATE orders SET delivery_date = ? WHERE id = ?",
                java.sql.Date.valueOf(date), orderId);
    }

    // payments.created_at is updatable=false in JPA, so backdate it with a native update.
    private void setPaymentCreatedAt(Long orderId, LocalDateTime when) {
        jdbcTemplate.update("UPDATE payments SET created_at = ? WHERE order_id = ?",
                java.sql.Timestamp.valueOf(when), orderId);
    }

    private long countSent(String needle) {
        return sentTexts.stream().filter(t -> t.contains(needle)).count();
    }

    @Test
    void earlyReminder_fires_once_for_advance_order() {
        Long orderId = driveToPaymentQr();
        setDeliveryDate(orderId, LocalDate.now().plusDays(3));            // 2+ days out
        setPaymentCreatedAt(orderId, LocalDateTime.now().minusHours(2));  // QR sent >1h ago
        sentTexts.clear();

        paymentReminderJob.earlyReminder();
        assertThat(countSent("isn't confirmed yet")).as("early nudge sent").isEqualTo(1);

        paymentReminderJob.earlyReminder();
        assertThat(countSent("isn't confirmed yet")).as("deduped — no second nudge").isEqualTo(1);
    }

    @Test
    void earlyReminder_skips_next_day_order() {
        Long orderId = driveToPaymentQr();
        LocalDateTime placedAt = LocalDateTime.now().minusHours(2);       // QR sent >1h ago
        setPaymentCreatedAt(orderId, placedAt);
        setDeliveryDate(orderId, placedAt.toLocalDate().plusDays(1));     // next-day from the order day (midnight-safe)
        sentTexts.clear();

        paymentReminderJob.earlyReminder();
        assertThat(countSent("isn't confirmed yet")).as("next-day skips the early nudge").isZero();
    }

    @Test
    void lastChance_fires_for_tomorrow_order_placed_earlier() {
        Long orderId = driveToPaymentQr();
        setDeliveryDate(orderId, LocalDate.now().plusDays(1));            // delivering tomorrow
        setPaymentCreatedAt(orderId, LocalDate.now().atTime(9, 0));       // placed well before cutoff
        sentTexts.clear();

        paymentReminderJob.lastChanceReminder();
        assertThat(countSent("Last reminder")).as("last-chance sent").isEqualTo(1);

        paymentReminderJob.lastChanceReminder();
        assertThat(countSent("Last reminder")).as("deduped").isEqualTo(1);
    }

    @Test
    void lastChance_skips_order_placed_within_an_hour_of_cutoff() {
        Long orderId = driveToPaymentQr();
        setDeliveryDate(orderId, LocalDate.now().plusDays(1));            // delivering tomorrow
        setPaymentCreatedAt(orderId, LocalDate.now().atTime(22, 30));     // within 1h of the 23:00 test cutoff
        sentTexts.clear();

        paymentReminderJob.lastChanceReminder();
        assertThat(countSent("Last reminder")).as("skipped — placed too close to cutoff").isZero();
    }

    @Test
    void reminders_skip_already_paid_orders() {
        Long orderId = driveToPaymentQr();
        setDeliveryDate(orderId, LocalDate.now().plusDays(3));
        setPaymentCreatedAt(orderId, LocalDateTime.now().minusHours(2));
        // Simulate the customer having shared a screenshot (order left PENDING_CONFIRMATION).
        Order order = orders.findById(orderId).orElseThrow();
        order.setStatus(com.tranche.bakery.order.OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
        orders.save(order);
        sentTexts.clear();

        paymentReminderJob.earlyReminder();
        assertThat(countSent("isn't confirmed yet")).as("no nudge once a screenshot is in").isZero();
    }
}
