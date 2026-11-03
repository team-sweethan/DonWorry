package com.donworry.fds.api;

import java.time.Instant;

// P2가 소유한 평가 이력 목록 항목이다. 결제·Case 상태는 포함하지 않는다.
public record FdsEvaluationSummary(
        Long evaluationId,
        Long paymentId,
        Long merchantId,
        String customerRef,
        Integer riskScore,
        FdsResult result,
        Instant evaluatedAt,
        String errorCode,
        Instant paymentRequestedAt) {}
