package com.donworry.fds.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record FdsEvaluationRequest(
        UUID evaluationRequestId, Long paymentId, String customerRef, long amount, Instant paymentRequestedAt) {

    public FdsEvaluationRequest {
        Objects.requireNonNull(evaluationRequestId, "evaluationRequestId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        if (paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("customerRef must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Objects.requireNonNull(paymentRequestedAt, "paymentRequestedAt must not be null");
    }
}
