package com.donworry.investigation.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// 고객사 "보안 조치 결과" 목록 — 로그인한 사장님 가맹점의 Case만 담는다 (fds 정본 02 §2.12 ②)
// 고객 참조 · 금액 · 점수 · 보고서 상태는 보내지 않는다. 결제별 내용은 상세에서 본다
public record MerchantCaseListResponse(Overview overview, List<CaseRow> cases) {

    // 조사 중 Case 수와 결제별 직원 판정 건수. FDS 차단(DENY) 건수는 Case가 없는 P2 평가라 여기 없다
    public record Overview(int investigatingCount, long acceptDecisionCount, long rejectDecisionCount) {}

    // displayStatus는 INVESTIGATING(조사 중) · PROCESSING(판정 완료 · 처리 진행 중) · CLOSED(조사 종료)
    @Builder
    public record CaseRow(Long caseId, String displayStatus, Instant createdAt, Instant closedAt, int paymentCount) {}
}
