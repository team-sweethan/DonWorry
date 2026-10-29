package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// AI Tool get_fds_evidence 응답 — 결제별 P2 FDS 점수 · 판정 · 적중 룰 (ADR-0009 Tool 6종)
// FDS 평가를 못 찾은 결제는 점수가 null이고 missingPaymentIds에도 들어간다. FDS 점수는 부정거래 확률이 아니다
public record FdsEvidenceToolResponse(
        String caseId,
        int evidenceVersion,
        String source,
        Instant retrievedAt,
        List<Payment> payments,
        List<String> missingPaymentIds) {

    public record Payment(String paymentId, Integer riskScore, String fdsResult, List<RuleHit> ruleHits) {}

    // 적중 룰 이름은 평가 당시 이름, appliedScore는 이 룰이 점수에 더한 값
    // criterion · observed는 코드가 만든 기준 · 실제 문장 (#326). 숫자를 정리할 수 없는 규칙은 null
    @Builder
    public record RuleHit(String code, String name, Integer appliedScore, String criterion, String observed) {}
}
