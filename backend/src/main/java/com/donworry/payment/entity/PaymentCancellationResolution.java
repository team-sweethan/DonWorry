package com.donworry.payment.entity;

public enum PaymentCancellationResolution {
    DEFERRED,
    REVIEW_REQUIRED,
    REJECTED,
    ACCEPTED,
    LINKED,
    LEGACY_VOID
}
