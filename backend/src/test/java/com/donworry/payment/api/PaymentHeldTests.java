package com.donworry.payment.api;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentHeldTests {
    // 빌더로 필수 필드를 누락해도 불완전한 이벤트를 만들 수 없다.
    @ParameterizedTest
    @ValueSource(
            strings = {"paymentId", "merchantId", "customerRef", "amount", "authorizationRef", "evaluationId", "heldAt"
            })
    void build_whenRequiredFieldMissing_rejectsEvent(String field) {
        var builder = PaymentHeld.builder()
                .paymentId(1L)
                .merchantId(2L)
                .customerRef("customer")
                .amount(1000L)
                .authorizationRef("auth")
                .evaluationId(3L)
                .heldAt(Instant.parse("2026-10-25T00:00:00Z"));
        switch (field) {
            case "paymentId" -> builder.paymentId(null);
            case "merchantId" -> builder.merchantId(null);
            case "customerRef" -> builder.customerRef(null);
            case "amount" -> builder.amount(null);
            case "authorizationRef" -> builder.authorizationRef(null);
            case "evaluationId" -> builder.evaluationId(null);
            case "heldAt" -> builder.heldAt(null);
            default -> throw new AssertionError(field);
        }
        assertThatNullPointerException().isThrownBy(builder::build).withMessage(field);
    }
}
