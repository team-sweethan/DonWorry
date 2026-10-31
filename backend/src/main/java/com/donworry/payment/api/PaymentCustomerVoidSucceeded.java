package com.donworry.payment.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Builder;

// 고객 HELD 승인 취소만 나타낸다. 직원 판정 ID나 직원 금융 조치 ID로 사용하지 않는다.
@Builder
public record PaymentCustomerVoidSucceeded(
        Long cancellationId, Long paymentId, Long merchantId, Long amount, Instant completedAt) {
    public PaymentCustomerVoidSucceeded {
        if (cancellationId == null
                || cancellationId <= 0
                || paymentId == null
                || paymentId <= 0
                || merchantId == null
                || merchantId <= 0
                || amount == null
                || amount <= 0
                || completedAt == null
                || !completedAt.equals(completedAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("고객 승인 취소 성공 근거가 유효하지 않습니다");
        }
    }
}
