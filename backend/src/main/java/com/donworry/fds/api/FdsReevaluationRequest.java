package com.donworry.fds.api;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record FdsReevaluationRequest(
        UUID originalEvaluationRequestId,
        UUID newEvaluationRequestId,
        Long paymentId,
        Long merchantId,
        String customerRef,
        long amount,
        Instant paymentRequestedAt,
        UUID retryId) {

    public FdsReevaluationRequest {
        if (originalEvaluationRequestId == null || newEvaluationRequestId == null || retryId == null) {
            throw new IllegalArgumentException("evaluation request IDs and retryId must not be null");
        }
        if (originalEvaluationRequestId.equals(newEvaluationRequestId)) {
            throw new IllegalArgumentException("newEvaluationRequestId must differ from originalEvaluationRequestId");
        }
        new FdsEvaluationRequest(
                newEvaluationRequestId, paymentId, merchantId, customerRef, amount, paymentRequestedAt);
    }

    public FdsEvaluationRequest evaluationRequest() {
        return new FdsEvaluationRequest(
                newEvaluationRequestId, paymentId, merchantId, customerRef, amount, paymentRequestedAt);
    }
}
