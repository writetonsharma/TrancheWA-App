package com.tranche.bakery.payment;

public enum PaymentStatus {
    PENDING,
    SCREENSHOT_RECEIVED,
    SCREENSHOT_VERIFIED,
    REVIEW_REQUIRED,
    CONFIRMED,
    FAILED
}
