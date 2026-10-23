package com.donworry.investigation.service;

import java.time.Instant;
import java.util.Objects;

// P5가 보낼 HELD 결제 통지의 P3 쪽 가정 형식 (HELD 시나리오 §4.3)
// P5 PaymentHeld 이벤트가 payment.api에 생기면 리스너가 이 값으로 바꿔 CaseIntakeService에 넘긴다
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
