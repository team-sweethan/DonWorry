package com.donworry.fds.api;

import java.util.Set;

// 후보 결제 중 사건 결제와 카드 · 기기 · IP가 같은 것 (#821) — 결제 번호만 싣는다
public record FdsPaymentMatches(Kind card, Kind device, Kind ip) {

    // provided: 사건 결제가 이 값을 하나라도 보냈는지 — false면 비교하지 않았다는 뜻이다 ("정보 없음", 일치 없음과 다르다)
    // paymentIds: 일치한 후보 결제 번호
    public record Kind(boolean provided, Set<Long> paymentIds) {

        public Kind {
            paymentIds = Set.copyOf(paymentIds);
        }

        public static Kind notProvided() {
            return new Kind(false, Set.of());
        }
    }
}
