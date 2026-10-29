package com.donworry.fds.api;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// 결제별 최신 평가와 Case 화면에 표시할 적중 룰 근거다.
// signals는 가맹점이 보낸 카드 · 기기 · IP의 표시용 요약이다 (#346). 원문 식별자는 싣지 않는다
public record FdsPaymentEvidence(
        Long paymentId,
        Long evaluationId,
        FdsResult result,
        Integer riskScore,
        Instant evaluatedAt,
        List<MatchedRule> matchedRules,
        PaymentSignals signals) {

    // signals 이전 호출용 — 요약 없이 만든다
    public FdsPaymentEvidence(
            Long paymentId,
            Long evaluationId,
            FdsResult result,
            Integer riskScore,
            Instant evaluatedAt,
            List<MatchedRule> matchedRules) {
        this(paymentId, evaluationId, result, riskScore, evaluatedAt, matchedRules, null);
    }

    // 카드는 마스킹 표시값, IP는 국가 코드만 싣는다. 카드 토큰 · IP 주소 · 기기 ID · 이메일 원문은 넣지 않는다 (#346)
    // *Provided는 가맹점이 그 값을 보냈는지 여부 — 안 보낸 값을 화면 · AI가 "없음"으로 구분하게
    public record PaymentSignals(
            String cardDisplayLabel,
            boolean cardProvided,
            boolean deviceProvided,
            String ipCountryCode,
            boolean ipProvided) {}

    // thresholdSnapshot · observedValue는 평가 당시 저장한 기준값 · 실제값 JSON 원본이다 (#326). 없으면 null
    // 차단 목록 규칙의 실제값에는 카드 토큰 · IP 같은 식별자가 들어 있다 — 받는 쪽이 골라서 쓴다
    // 같은 String 칸(기준값 · 실제값)이 나란히 있어 순서 실수를 막으려고 builder로 만든다
    @Builder
    public record MatchedRule(
            String ruleCode, String ruleName, int appliedScore, String thresholdSnapshot, String observedValue) {}
}
