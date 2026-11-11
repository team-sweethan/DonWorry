package com.donworry.investigation.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// 고객사 "보안 조치 결과" 상세 (fds 정본 02 §2.12 ②)
// 적중 규칙 · 증거 추적 · 판정 사유 · 판정 직원 · FDS 점수 · 내부 ID · 인계 실패 코드는 보내지 않는다
// AI 검토 권고(aiRecommendation)는 #644에서 뺐다 — AI 보고서가 판정 권고를 내지 않는다 (ADR-0009 결정 13, ADR-0019)
@Builder
public record MerchantCaseDetailResponse(
        Long caseId,
        String displayStatus,
        Instant createdAt,
        Instant closedAt,
        // 사건 상태만으로 만든 사장님용 처리 안내. 내부 AI 요약과 조사 방식은 내보내지 않는다.
        List<String> summary,
        List<PaymentResult> payments) {

    // 직원 판정(decision)과 실제 금융 처리 결과(paymentStatus, P5 정본)를 따로 보낸다
    // decision은 ACCEPT(정상 처리 결정) · REJECT(취소 결정) · null(미판정)
    // 결제 정보를 조회하지 못하면 amount · requestedAt · paymentStatus가 null이다
    @Builder
    public record PaymentResult(
            Long paymentId,
            Long amount,
            Instant requestedAt,
            String paymentStatus,
            String decision,
            // 고객이 가맹점에서 직접 승인을 취소한 결제(#382 · #439). 직원 거절(REJECT)과 구분한다
            boolean customerCanceled) {}
}
