package com.donworry.fds.api;

import java.util.List;

// 평가 당시 저장한 룰 실행 근거와 같은 결제의 평가 이력을 함께 제공한다.
public record FdsEvaluationDetail(
        FdsEvaluationSummary evaluation,
        List<FdsRuleHitDetail> ruleHits,
        List<FdsEvaluationSummary> history,
        List<FdsBlocklistCandidate> blocklistCandidates,
        CardIdentifier cardIdentifier) {

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
}
