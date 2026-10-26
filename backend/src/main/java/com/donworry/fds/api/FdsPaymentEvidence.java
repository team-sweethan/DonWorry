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

    public record MatchedRule(String ruleCode, String ruleName, int appliedScore) {}
}
