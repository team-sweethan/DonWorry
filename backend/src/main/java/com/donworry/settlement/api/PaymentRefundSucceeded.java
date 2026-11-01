package com.donworry.settlement.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Builder;

// 고객에게 돌려준 전액이며 P6 정산 차감액이 아니다. 재전달에서도 저장된 식별자와 시각을 유지한다.
@Builder
public record PaymentRefundSucceeded(
        Long cancellationId,
        Long paymentId,
        Long captureId,
        Long merchantId,
        Long cancellationAmount,
        Instant canceledAt) {
    public PaymentRefundSucceeded {
        if (cancellationId == null
                || cancellationId <= 0
                || paymentId == null
                || paymentId <= 0
                || captureId == null
                || captureId <= 0
                || merchantId == null
                || merchantId <= 0
                || cancellationAmount == null
                || cancellationAmount <= 0
                || canceledAt == null
                || !canceledAt.equals(canceledAt.truncatedTo(ChronoUnit.MICROS)))
            throw new IllegalArgumentException("환불 성공 근거가 유효하지 않습니다");
    }
}
