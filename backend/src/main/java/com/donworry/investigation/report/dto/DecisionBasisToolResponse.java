package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// AI Tool get_decision_basis 응답 — 결제별 징후 · 정상 근거 (ADR-0009 결정 7 · 11, #618)
// Spring이 계산한다. ai-server는 받아서 보고서에 그대로 싣고 바꾸지 않는다
// 기준표 칸(rule) · 권고(recommendation)는 #644에서 뺐다 (결정 13) — 판정은 보안 직원이 한다
// 금액 · 요청 시각을 모르거나 FDS 근거가 없는 결제는 payments에 없다 — 받는 쪽은 한계 줄로 알린다
// 키 이름은 보고서 paymentAnalyses[].basis(frontend/src/types/case.ts DecisionBasis)와 같다
@Builder
public record DecisionBasisToolResponse(
        String caseId, int evidenceVersion, String source, Instant retrievedAt, List<PaymentBasis> payments) {

    public record PaymentBasis(String paymentId, Basis basis) {}

    public record Basis(List<Signal> signals, List<Normal> normals) {}

    // level: HIGH_RISK(고위험) · CAUTION(주의). normalReason이 null이면 "정상 사유 없음"
    // countedIn: 같은 행동을 이미 센 징후 이름. checkBefore: 판정 전에 확인할 것 (#591)
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
