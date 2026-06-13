package com.tranche.bakery.admin;

import com.tranche.bakery.feedback.Feedback;

import java.time.LocalDate;
import java.util.List;

public record AdminDashboard(
        LocalDate today,
        List<AdminOrderView> deliveringToday,
        List<AdminOrderView> deliveringTomorrow,
        List<AdminOrderView> paymentReview,
        List<AdminOrderView> stuckDrafts,
        List<AdminOrderView> awaitingScreenshot,
        List<Feedback> messages
) {}
