package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationResponse;
import java.time.Instant;
import java.util.Objects;
import lombok.Builder;

@Builder
public record PaymentObservedEvaluation(FdsEvaluationResponse evaluation, Instant requestedAt, Instant respondedAt) {
    public PaymentObservedEvaluation {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(respondedAt, "respondedAt");
    }
}
