package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentStatus;
import java.util.Objects;
import java.util.UUID;

// 같은 저장·평가 호출에서 만든 문맥만 service 패키지 안에서 전달한다.
record PaymentAuthorizationContext(
        PaymentCreationSnapshot payment, UUID evaluationRequestId, PaymentEvaluationResult evaluationResult) {
    PaymentAuthorizationContext {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(evaluationRequestId, "evaluationRequestId");
        Objects.requireNonNull(evaluationResult, "evaluationResult");
        if (payment.paymentId() == null
                || payment.paymentId() <= 0
                || payment.amount() == null
                || payment.amount() <= 0
                || payment.customerRef() == null
                || payment.customerRef().isBlank()
                || payment.createdAt() == null
                || payment.status() != PaymentStatus.PENDING
                || payment.authorizationRef() != null) {
            throw new IllegalArgumentException("invalid initial payment snapshot");
        }
        if (!evaluationRequestId.equals(evaluationResult.evaluation().evaluationRequestId())) {
            throw new IllegalArgumentException("FDS evaluation request ID mismatch");
        }
    }
}
