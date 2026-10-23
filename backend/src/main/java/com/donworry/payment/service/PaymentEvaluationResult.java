package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationResponse;
import java.util.Objects;

public record PaymentEvaluationResult(FdsEvaluationResponse evaluation, PaymentRules.Decision decision) {
    public PaymentEvaluationResult {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(decision, "decision");
    }
}
