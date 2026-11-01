package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentRiskSignals;
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
                .merchantId(42L)
                .customerRef("customer-001")
                .amount(150_000L)
                .paymentRequestedAt(REQUESTED_AT)
                .riskSignals(riskSignals());
    }

    static PaymentRiskSignals riskSignals() {
        return PaymentRiskSignals.builder()
                .deviceId("device-test-001")
                .cardToken("card-token-test-001")
                .cardDisplayLabel("테스트 **** 0001")
                .ipCountryCode("KR")
                .ipAddress("203.0.113.10")
                .buyerEmail("buyer@example.test")
                .build();
    }
}
