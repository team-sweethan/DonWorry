package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;

// AI Tool find_linked_payments 응답 — 이 사건 결제와 같은 카드 · 기기 · IP를 쓴 같은 가맹점 다른 고객 결제 집계 (#352, ADR-0009)
// 건수 · 고객 수 · 판정만 싣는다. 카드 토큰 · IP 주소 · 기기 ID · 다른 고객 번호 · 다른 결제 ID는 싣지 않는다
public record LinkedPaymentsToolResponse(
        String caseId,
        int evidenceVersion,
        String source,
        Instant retrievedAt,
        int lookbackDays,
        Match card,
        Match device,
        Match ip) {

    // provided=false면 이 사건 결제가 그 값을 보내지 않아 비교하지 않았다는 뜻이다 ("정보 없음")
    // resultCounts는 FDS 판정 순서(허용 · 검토 · 차단 · 평가 오류), declinedCount는 카드사 거절 결제 수
    public record Match(
            boolean provided,
            int otherCustomerCount,
            int paymentCount,
            List<ResultCount> resultCounts,
            int declinedCount) {}

    public record ResultCount(String result, int count) {}
}
