package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentFdsRecovery;
import com.donworry.payment.entity.PaymentFdsRecoveryStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

// 후속 실행 서비스에 전달할 내부 스냅샷이며 HTTP 응답으로 노출하지 않는다.
@Builder
public record PaymentFdsReevaluationAdmission(
        Long paymentId,
        Long recoveryId,
        PaymentFdsRecoveryStatus status,
        UUID newEvaluationRequestId,
        UUID retryId,
        Long requestedBy,
        Instant requestedAt) {
    static PaymentFdsReevaluationAdmission from(PaymentFdsRecovery recovery) {
        return new PaymentFdsReevaluationAdmission(
                recovery.getPaymentId(),
                recovery.getRecoveryId(),
                recovery.getStatus(),
                recovery.getNewEvaluationRequestId(),
                recovery.getRetryId(),
                recovery.getRequestedBy(),
                recovery.getRequestedAt());
    }
}
