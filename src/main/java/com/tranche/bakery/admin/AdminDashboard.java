package com.tranche.bakery.admin;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.payment.Payment;

import java.time.LocalDate;
import java.util.List;

public record AdminDashboard(
        LocalDate today,
        List<AdminOrderView> deliveringToday,
        List<AdminOrderView> deliveringTomorrow,
        List<AdminOrderView> paymentReview,
        List<AdminOrderView> stuckDrafts,
        List<AdminOrderView> awaitingScreenshot
) {}
