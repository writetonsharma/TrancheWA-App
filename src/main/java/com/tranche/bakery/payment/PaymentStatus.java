package com.tranche.bakery.payment;

public enum PaymentStatus {
    PENDING,
    SCREENSHOT_RECEIVED,
    SCREENSHOT_VERIFIED,
    REVIEW_REQUIRED,
    CONFIRMED,
    FAILED,
    /** Payment captured by the gateway (Razorpay) — no screenshot involved. */
    GATEWAY_CAPTURED
}
