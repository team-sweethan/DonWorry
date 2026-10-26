package com.donworry.investigation.dto;

import java.time.Instant;
import java.util.List;

// 고객사 "보안 조치 결과" 상세 (fds 정본 02 §2.12 ②)
// 적중 규칙 · 증거 추적 · 판정 사유 · 판정 직원 · FDS 점수 · 내부 ID · 인계 실패 코드는 보내지 않는다
public record MerchantCaseDetailResponse(
        Long caseId,
        String displayStatus,
        Instant createdAt,
        Instant closedAt,
        // 최신 보고서가 성공했을 때의 "핵심 요약". 조사 중이거나 보고서가 실패했으면 null
        List<String> summary,
        // AI 보고서 없이 보안 직원이 직접 조사해 판정한 결제가 있으면 true — 화면은 "보안 담당자 직접 조사"로 표시한다
        boolean directInvestigation,
        List<PaymentResult> payments) {

    // 직원 판정(decision)과 실제 금융 처리 결과(paymentStatus, P5 정본)를 따로 보낸다
    // decision은 ACCEPT(정상 처리 결정) · REJECT(취소 결정) · null(미판정)
    // 결제 정보를 조회하지 못하면 amount · requestedAt · paymentStatus가 null이다
    public record PaymentResult(
            Long paymentId,
            Long amount,
            Instant requestedAt,
            String paymentStatus,
            String decision,
            String aiRecommendation) {}
}
