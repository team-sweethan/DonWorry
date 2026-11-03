package com.donworry.payment.dto;

import com.donworry.payment.entity.*;
import java.time.Instant;
import lombok.Builder;

@Builder
public record PaymentFdsRecoveryResponse(
        Long paymentId,
        Long recoveryId,
        String status,
        String evaluationResult,
        String paymentStatus,
        String reasonCode,
        Instant completedAt) {
    public static PaymentFdsRecoveryResponse from(
            PaymentFdsRecovery recovery, Payment payment, PaymentRequest request) {
        boolean hasNewEvaluation = recovery.getNewEvaluationRequestId() != null
                && request != null
                && recovery.getNewEvaluationRequestId().equals(request.getEvaluationRequestId());
        String result = hasNewEvaluation ? request.getEvaluationResult() : null;
        String reason = recovery.getStatus() == PaymentFdsRecoveryStatus.COMPLETED
                ? null
                : "ERROR".equals(result) ? request.getEvaluationErrorCode() : recovery.getReasonCode();
        return new PaymentFdsRecoveryResponse(
                payment.getPaymentId(),
                recovery.getRecoveryId(),
                recovery.getStatus().name(),
                result,
                payment.getStatus().name(),
                reason,
                recovery.getCompletedAt());
    }
}
