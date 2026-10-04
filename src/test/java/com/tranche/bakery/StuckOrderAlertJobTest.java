package com.tranche.bakery;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.alert.StuckOrderAlertJob;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class StuckOrderAlertJobTest extends FlowScenarioBase {

    @Autowired StuckOrderAlertJob job;
    @Autowired AlertService alertService;

    @Test
    void draftReminder_sentOnce_evenAfterTheAlertIsResolved() {
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.DRAFT);
        order = orderRepository.save(order);
        // Past the 2h stuck-draft threshold (created_at is insert-only, so set it natively).
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(3)), order.getId());

        job.checkStuckDrafts();
        assertThat(sentTexts.stream().filter(t -> t.contains("haven't finished")).count())
                .as("one draft reminder on first run").isEqualTo(1);

        // Admin tidies the dashboard — this used to let the reminder re-fire on the next run.
        alertService.resolveAll();

        job.checkStuckDrafts();
        assertThat(sentTexts.stream().filter(t -> t.contains("haven't finished")).count())
                .as("no repeat reminder after the alert is resolved").isEqualTo(1);
    }
}
