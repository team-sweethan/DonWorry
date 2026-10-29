package com.donworry.fds.api;

import java.util.Map;

// 이 사건 결제와 같은 카드 · 기기 · IP를 쓴 같은 가맹점 다른 고객 결제의 집계 (#352)
// 숫자 · 판정 코드만 싣는다 — 카드 토큰 · IP 주소 · 기기 ID · 다른 고객 번호 원문을 실을 칸이 없다
public record FdsLinkedPayments(Match card, Match device, Match ip) {

    // provided: 이 사건 결제가 그 값을 하나라도 보냈는지 — false면 비교하지 않았다는 뜻이다 ("정보 없음")
    // resultCounts는 다른 결제마다 최신 판정(최신 비오류 평가) 하나씩 센 값, declinedCount는 카드사 거절 결제 수
    public record Match(
            boolean provided,
            int otherCustomerCount,
            int paymentCount,
            Map<FdsResult, Integer> resultCounts,
            int declinedCount) {

        public static Match notProvided() {
            return new Match(false, 0, 0, Map.of(), 0);
        }
    }
}
