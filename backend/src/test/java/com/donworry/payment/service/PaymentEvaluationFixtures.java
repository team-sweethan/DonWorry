package com.donworry.payment.service;

import java.time.Instant;
import java.util.UUID;

final class PaymentEvaluationFixtures {

    static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    static final Instant REQUESTED_AT = Instant.parse("2026-10-23T00:00:00Z");

    private PaymentEvaluationFixtures() {}

    static PaymentEvaluationInput.PaymentEvaluationInputBuilder validInput() {
        return PaymentEvaluationInput.builder()
                .evaluationRequestId(REQUEST_ID)
                .paymentId(101L)
                .merchantId(1L)
                .customerRef("customer-001")
                .amount(150_000L)
                .paymentRequestedAt(REQUESTED_AT);
    }
}
