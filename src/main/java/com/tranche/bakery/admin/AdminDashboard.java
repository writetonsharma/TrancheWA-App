package com.tranche.bakery.admin;

import java.time.LocalDate;
import java.util.List;

import com.tranche.bakery.alert.Alert;
import com.tranche.bakery.feedback.Feedback;

public record AdminDashboard(
        LocalDate today,
        List<AdminOrderView> deliveringToday,
        List<AdminOrderView> deliveringTomorrow,
        List<AdminOrderView> paymentReview,
        List<AdminOrderView> stuckDrafts,
        List<AdminOrderView> awaitingScreenshot,
        List<Feedback> messages,
        List<Alert> alerts,
        List<BakeListItem> bakeListTomorrow,
        List<AdminOrderView> orderHistory,
        List<AdminOrderView> futureDeliveries,
        List<AdminOrderView> noDeliveryDate
) {}
