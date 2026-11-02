package com.donworry.investigation.report.dto;

import com.donworry.investigation.report.entity.MlShadowScore;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;

// 거래 내역 "ML 참고" 칸 한 줄 (#441) — 결제 판정이 아닌 참고 점수다 (ADR-0061 결정 1)
// 확률 · 기준값 · 등급은 채점 성공(SUCCEEDED)일 때만 있고, 실패 · 건너뜀은 null + failureCode. 모델 입력값(features)은 내보내지 않는다
@Builder
public record MlShadowScoreResponse(
        Long paymentId,
        String modelVersion,
        String status,
        String mlRiskLevel,
        BigDecimal fraudProbability,
        BigDecimal threshold,
        String failureCode,
        Instant scoredAt) {

    public static MlShadowScoreResponse from(MlShadowScore score) {
        return MlShadowScoreResponse.builder()
                .paymentId(score.getPaymentId())
                .modelVersion(score.getModelVersion())
                .status(score.getStatus().name())
                .mlRiskLevel(
                        score.getMlRiskLevel() == null
                                ? null
                                : score.getMlRiskLevel().name())
                .fraudProbability(score.getFraudProbability())
                .threshold(score.getThreshold())
                .failureCode(score.getFailureCode())
                .scoredAt(score.getScoredAt())
                .build();
    }
}
