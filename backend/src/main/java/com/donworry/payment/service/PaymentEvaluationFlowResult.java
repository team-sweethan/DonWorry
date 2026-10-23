package com.donworry.payment.service;

import java.util.Objects;

public record PaymentEvaluationFlowResult(Long paymentId, PaymentEvaluationResult evaluationResult) {

    public PaymentEvaluationFlowResult {
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        Objects.requireNonNull(evaluationResult, "evaluationResult");
    }
}
