package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// AI Tool get_decision_basis 응답 — 결제별 판정 근거 · 판정 기준표 칸 · 권고 (ADR-0009 결정 7, #618)
// 기준표는 Spring이 계산한다. ai-server는 받아서 보고서에 그대로 싣고 바꾸지 않는다
// 금액 · 요청 시각을 모르거나 FDS 근거가 없는 결제는 payments에 없다 — 받는 쪽은 그 결제를 추가 확인으로 둔다
// 키 이름은 보고서 paymentAnalyses[].basis(frontend/src/types/case.ts DecisionBasis)와 같다
@Builder
public record DecisionBasisToolResponse(
        String caseId, int evidenceVersion, String source, Instant retrievedAt, List<PaymentBasis> payments) {

    public record PaymentBasis(String paymentId, Basis basis) {}

    // rule: 판정 기준표에서 해당한 칸 (HIGH_RISK_WITH_OTHERS · HIGH_RISK_ONLY · CAUTION_ONLY · ALL_EXPLAINED)
    // recommendation: 칸의 권고 (REJECT_REVIEW · NEEDS_CHECK · ACCEPT_REVIEW) — 참고용, 판정은 직원이 한다
    @Builder
    public record Basis(List<Signal> signals, List<Normal> normals, String rule, String recommendation) {}

    // level: HIGH_RISK(고위험) · CAUTION(주의). normalReason이 null이면 "정상 사유 없음"
    // countedIn: 같은 행동을 이미 센 징후 이름 — 판정에 따로 세지 않는다. checkBefore: 판정 전에 확인할 것 (#591)
    @Builder
    public record Signal(
            String key,
            String label,
            String detail,
            String level,
            String normalReason,
            String countedIn,
            String checkBefore) {}

    // canExplain: 설명할 수 있는 징후, explains: 이 결제에서 실제로 설명한 징후
    @Builder
    public record Normal(String key, String label, List<String> canExplain, List<String> explains) {}
}
