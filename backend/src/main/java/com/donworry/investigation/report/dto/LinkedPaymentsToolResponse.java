package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// AI Tool find_linked_payments 응답 — 이 사건 결제와 같은 카드 · 기기 · IP를 쓴 같은 가맹점 다른 고객 결제 집계 (#352, ADR-0009)
// 건수 · 고객 수 · 판정만 싣는다. 카드 토큰 · IP 주소 · 기기 ID · 다른 고객 번호 · 다른 결제 ID는 싣지 않는다
// 칸이 많고 같은 타입(int · Match)이 나란히 있어 builder로 만든다 (02-code.md @Builder)
@Builder
public record LinkedPaymentsToolResponse(
        String caseId,
        int evidenceVersion,
        String source,
        Instant retrievedAt,
        int lookbackDays,
        // 각 Match.nearby가 센 "사건 결제 앞뒤" 시간 (#637)
        int nearbyWindowMinutes,
        Match card,
        Match device,
        Match ip,
        List<PaymentLinks> payments) {

    // 사건 결제 하나의 집계 — card · device · ip는 사건 전체, 이 칸은 그 결제의 카드 · 기기 · IP로 센 값 (#533)
    // paymentId는 이 사건의 결제다. ai-server가 결제마다 그 결제의 징후만 붙인다
    public record PaymentLinks(String paymentId, Match card, Match device, Match ip) {}

    // provided=false면 이 사건 결제가 그 값을 보내지 않아 비교하지 않았다는 뜻이다 ("정보 없음")
    // resultCounts는 FDS 판정 순서(허용 · 검토 · 차단 · 평가 오류), declinedCount는 카드사 거절 결제 수
    // nearby: 그중 사건 결제 요청 시각 앞뒤 nearbyWindowMinutes 안의 결제만 다시 센 값 (#637)
    @Builder
    public record Match(
            boolean provided,
            int otherCustomerCount,
            int paymentCount,
            List<ResultCount> resultCounts,
            int declinedCount,
            Nearby nearby) {}

    // 사건 결제 앞뒤 시간 안의 다른 고객 결제 (#637) — 칸 뜻은 Match와 같다
    @Builder
    public record Nearby(int otherCustomerCount, int paymentCount, List<ResultCount> resultCounts, int declinedCount) {}

    public record ResultCount(String result, int count) {}
}
