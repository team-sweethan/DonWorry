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

    // 화면은 원문을 받지 않는다 (#603). 등록은 평가 ID와 종류만 보내고 서버가 평가에서 값을 찾는다
    // displayValue: 카드는 표시값(없으면 null), 이메일 · IP · 기기 ID는 가린 값, 고객 번호는 그대로 (#603 · #628)
    // blocked: 지금 활성 차단 목록에 있는지
    public record FdsBlocklistCandidate(String identifierType, String displayValue, boolean blocked) {}

    // 카드 토큰은 내보내지 않는다 (#628). 사람이 볼 값은 표시값(VISA •••• 1234)이다
    public record CardIdentifier(String cardDisplayLabel) {}

    // ERROR 평가의 P2 자동 분석 결과다. 복구 상태는 P5 공개 조회에서 별도로 제공한다.
    public record FdsFailureAnalysisDetail(FdsFailureDisposition disposition, String reasonCode, Instant analyzedAt) {}
}
