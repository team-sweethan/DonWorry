package com.donworry.payment.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PaymentActionResult(
        Long paymentId,
        Long decisionId,
        String actionType,
        UUID idempotencyKey,
        String status,
        Long targetOperationId,
        String errorCode,
        Instant completedAt) {
    public PaymentActionResult {
        new PaymentActionCommand(paymentId, decisionId, actionType, idempotencyKey);
        new PaymentActionReceipt(status, targetOperationId, errorCode);
        if ("PENDING".equals(status) || completedAt == null) {
            throw new IllegalArgumentException("완료된 조치 결과와 시각이 필요합니다");
        }
        completedAt = completedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
