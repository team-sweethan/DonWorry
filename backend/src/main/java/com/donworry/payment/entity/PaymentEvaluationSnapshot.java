package com.donworry.payment.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;

// 다른 모듈 Entity를 참조하지 않는 P5 복원용 저장 스냅샷이다.
@Builder
public record PaymentEvaluationSnapshot(
        UUID requestId, Long evaluationId, String result, Integer riskScore, String errorCode, Instant evaluatedAt) {
    public PaymentEvaluationSnapshot {
        Objects.requireNonNull(requestId, "requestId");
        PaymentStorageValues.positive(evaluationId);
        if (result == null || !Set.of("ALLOW", "REVIEW", "DENY", "ERROR").contains(result))
            throw new IllegalArgumentException("평가 결과가 잘못됐습니다");
        evaluatedAt = PaymentStorageValues.micros(evaluatedAt);
        if (result.equals("ERROR")) {
            PaymentStorageValues.reference(errorCode);
            if (riskScore != null) throw new IllegalArgumentException("ERROR에는 점수가 없습니다");
        } else if (errorCode != null || riskScore == null || riskScore < 0) {
            throw new IllegalArgumentException("평가 점수와 오류가 일치하지 않습니다");
        }
    }
}
