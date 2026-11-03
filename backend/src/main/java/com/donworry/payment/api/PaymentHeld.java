package com.donworry.payment.api;

import java.time.Instant;
import java.util.Objects;
import lombok.Builder;

// REVIEW 승인 성공 후의 사실 통지다. heldAt은 P3의 Case 병합 기준 시각이 아니다.
@Builder
public record PaymentHeld(
        Long paymentId,
        Long merchantId,
        String customerRef,
        Long amount,
        String authorizationRef,
        Long evaluationId,
        Instant heldAt) {

    public PaymentHeld {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(customerRef, "customerRef");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(authorizationRef, "authorizationRef");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(heldAt, "heldAt");
    }
}
