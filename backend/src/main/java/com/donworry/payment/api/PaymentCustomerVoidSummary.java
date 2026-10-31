package com.donworry.payment.api;

import java.time.Instant;
import lombok.Builder;

// P5에 저장된 고객 승인 취소 성공 원본. 요청 키·사유·승인 참조는 공개하지 않는다.
@Builder
public record PaymentCustomerVoidSummary(
        Long cancellationId, Long paymentId, Long merchantId, Long amount, Instant completedAt) {}
