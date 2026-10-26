package com.donworry.investigation.service;

import java.time.Instant;
import java.util.Objects;

// P3 내부 HELD 결제 입력 — P5 PaymentHeld(ADR-0023)와 같은 7개 필드
// PaymentHeldListener와 시연 API가 이 형식으로 바꿔 CaseIntakeService에 넘긴다
public record HeldPayment(
        Long paymentId,
        Long merchantId,
        String customerRef,
        Long amount,
        String authorizationRef,
        Long evaluationId,
        Instant heldAt) {

    public HeldPayment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(customerRef, "customerRef");
        Objects.requireNonNull(heldAt, "heldAt");
    }
}
