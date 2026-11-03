package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentRiskSignals;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

// merchantId는 향후 HTTP 경계에서 인증 정보로 정한다. 외부 body를 신뢰하지 않는다.
@Builder
public record PaymentRequestCommand(
        Long merchantId,
        String orderRef,
        String customerRef,
        Long amount,
        Instant orderCreatedAt,
        UUID idempotencyKey,
        PaymentRiskSignals riskSignals) {
    public PaymentRequestCommand(
            Long merchantId,
            String orderRef,
            String customerRef,
            Long amount,
            Instant orderCreatedAt,
            UUID idempotencyKey) {
        this(merchantId, orderRef, customerRef, amount, orderCreatedAt, idempotencyKey, PaymentRiskSignals.none());
    }

    public PaymentRequestCommand {
        if (merchantId == null || merchantId <= 0 || amount == null || amount <= 0)
            throw new IllegalArgumentException("가맹점과 금액은 양수여야 합니다");
        requireReference(orderRef);
        requireReference(customerRef);
        orderCreatedAt =
                Objects.requireNonNull(orderCreatedAt, "orderCreatedAt").truncatedTo(ChronoUnit.MICROS);
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        riskSignals = PaymentRiskSignals.orNone(riskSignals);
    }

    private static void requireReference(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > 100)
            throw new IllegalArgumentException("참조는 양끝 공백 없이 1~100자여야 합니다");
    }
}
