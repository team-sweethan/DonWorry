package com.donworry.payment.api;

import java.time.Instant;
import lombok.Builder;

// requestedAt은 최초 결제 저장 시각이며 승인·매입 시각이 아니다.
@Builder
public record PaymentSummary(Long paymentId, Long amount, String paymentStatus, Instant requestedAt) {}
