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

    // signals: 카드 · 기기 · 접속 국가 요약 (#346). FDS 평가가 없으면 null
    public record Payment(
            String paymentId, Integer riskScore, String fdsResult, List<RuleHit> ruleHits, Signals signals) {}

    // 카드는 마스킹 표시값, IP는 국가 코드만 — 원문(카드 토큰 · IP 주소 · 기기 ID · 이메일)은 없다
    // *Provided는 가맹점이 그 값을 보냈는지 여부 (#346)
    public record Signals(
            String cardDisplayLabel,
            boolean cardProvided,
            boolean deviceProvided,
            String ipCountryCode,
            boolean ipProvided) {}

    // 적중 룰 이름은 평가 당시 이름, appliedScore는 이 룰이 점수에 더한 값
    // criterion · observed는 코드가 만든 기준 · 실제 문장 (#326). 숫자를 정리할 수 없는 규칙은 null
    @Builder
    public record RuleHit(String code, String name, Integer appliedScore, String criterion, String observed) {}
}
