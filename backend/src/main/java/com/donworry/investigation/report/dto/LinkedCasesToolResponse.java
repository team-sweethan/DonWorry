package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// AI Tool find_linked_cases 응답 — 이 사건 결제와 같은 카드 · 기기 · IP를 쓴 결제가 들어 있는 지난 종료 사건 (#821, ADR-0009)
// 범위: 같은 가맹점 · 다른 고객 · 종료 lookbackDays일 안 (같은 고객 사건은 find_similar_cases가 낸다)
// 사건 번호 · 종료 시각 · 겹친 종류 · 일치한 결제의 직원 판정 건수만 싣는다
// 결제 번호 · 고객 번호 · 판정 사유 글 · 카드 토큰 · IP · 기기 원문은 싣지 않는다. 사건 번호는 화면 링크용이고 ai-server가 AI 입력에서 뺀다
// by: CARD_DEVICE(코드 기본 조회) · IP(AI가 고르는 추가 조회). kinds는 이번에 비교한 종류, notProvided는 그중 사건 결제가 값을 보내지 않아 비교하지 못한 종류
@Builder
public record LinkedCasesToolResponse(
        String caseId,
        int evidenceVersion,
        String source,
        Instant retrievedAt,
        String by,
        int lookbackDays,
        List<String> kinds,
        List<String> notProvided,
        List<LinkedCase> cases,
        int totalMatched) {

    // matchedBy: 겹친 종류(CARD · DEVICE · IP), 결제 수 · 판정 건수는 그 사건 결제 중 겹친 결제만 센 값
    // 판정이 없는 결제(고객 직접 취소 등)는 paymentCount에만 들어간다
    @Builder
    public record LinkedCase(
            String caseId,
            List<String> matchedBy,
            Instant closedAt,
            int paymentCount,
            int acceptCount,
            int rejectCount) {}
}
