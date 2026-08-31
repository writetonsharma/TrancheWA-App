package com.tranche.bakery.subscription;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Daily: generate upcoming subscription delivery orders and close finished subscriptions. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionJob {

    private final SubscriptionService subscriptionService;

    @Scheduled(cron = "0 15 5 * * *", zone = "Asia/Kolkata")
    public void run() {
        subscriptionService.generateDueOrders();
        subscriptionService.completeFinished();
    }

    // Evening: clear out unpaid draft subscriptions so they don't block the customer re-subscribing.
    @Scheduled(cron = "0 0 20 * * *", zone = "Asia/Kolkata")
    public void expireDrafts() {
        subscriptionService.expireStalePendingPayments();
    }
}
