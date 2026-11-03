package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentRiskSignals;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PaymentEvaluationInput(
        UUID evaluationRequestId,
        Long paymentId,
        Long merchantId,
        String customerRef,
        long amount,
        Instant paymentRequestedAt,
        PaymentRiskSignals riskSignals) {

    public PaymentEvaluationInput {
        if (evaluationRequestId == null) {
            throw new IllegalArgumentException("evaluationRequestId must not be null");
        }
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("customerRef must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (paymentRequestedAt == null) {
            throw new IllegalArgumentException("paymentRequestedAt must not be null");
        }
        riskSignals = PaymentRiskSignals.orNone(riskSignals);
    }
}
