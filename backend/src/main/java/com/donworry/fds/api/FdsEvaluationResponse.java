package com.donworry.fds.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record FdsEvaluationResponse(
        UUID evaluationRequestId,
        Long evaluationId,
        FdsResult result,
        Integer riskScore,
        Instant evaluatedAt,
        String errorCode) {

    public FdsEvaluationResponse {
        Objects.requireNonNull(evaluationRequestId, "evaluationRequestId must not be null");
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");

        if (result == FdsResult.ERROR) {
            if (riskScore != null) {
                throw new IllegalArgumentException("ERROR result must not have riskScore");
            }
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("ERROR result must have errorCode");
            }
        } else {
            if (riskScore == null || riskScore < 0) {
                throw new IllegalArgumentException("non-error result must have non-negative riskScore");
            }
            if (errorCode != null) {
                throw new IllegalArgumentException("non-error result must not have errorCode");
            }
        }
    }
}
