package com.donworry.fds.api;

import java.time.Instant;
import java.util.List;

// 평가 당시 저장한 룰 실행 근거와 같은 결제의 평가 이력을 함께 제공한다.
public record FdsEvaluationDetail(
        FdsEvaluationSummary evaluation,
        List<FdsRuleHitDetail> ruleHits,
        List<FdsEvaluationSummary> history,
        List<FdsBlocklistCandidate> blocklistCandidates,
        CardIdentifier cardIdentifier,
        FdsFailureAnalysisDetail failureAnalysis) {

    public record FdsRuleHitDetail(
            Long hitId,
            String ruleCode,
            String ruleName,
            FdsRuleHitOutcome outcome,
            int appliedScore,
            String observedValue,
            String thresholdSnapshot) {}

    public record FdsBlocklistCandidate(String identifierType, String identifierValue, String displayLabel) {}

    public record CardIdentifier(String cardToken, String cardDisplayLabel) {}

    // ERROR 평가의 P2 자동 분석 결과다. 복구 상태는 P5 공개 조회에서 별도로 제공한다.
    public record FdsFailureAnalysisDetail(FdsFailureDisposition disposition, String reasonCode, Instant analyzedAt) {}
}
