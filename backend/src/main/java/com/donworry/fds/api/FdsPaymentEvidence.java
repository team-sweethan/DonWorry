package com.donworry.fds.api;

import java.time.Instant;
import java.util.List;

// 결제별 최신 평가와 Case 화면에 표시할 적중 룰 근거다.
public record FdsPaymentEvidence(
        Long paymentId,
        Long evaluationId,
        FdsResult result,
        Integer riskScore,
        Instant evaluatedAt,
        List<MatchedRule> matchedRules) {

    // thresholdSnapshot · observedValue는 평가 당시 저장한 기준값 · 실제값 JSON 원본이다 (#326). 없으면 null
    // 차단 목록 규칙의 실제값에는 카드 토큰 · IP 같은 식별자가 들어 있다 — 받는 쪽이 골라서 쓴다
    public record MatchedRule(
            String ruleCode, String ruleName, int appliedScore, String thresholdSnapshot, String observedValue) {}
}
