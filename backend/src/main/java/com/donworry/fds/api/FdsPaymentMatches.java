package com.donworry.fds.api;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 후보 결제 중 사건 결제와 카드 · 기기 · IP가 같은 것 (#821) — 결제 번호만 싣는다
public record FdsPaymentMatches(Kind card, Kind device, Kind ip) {

    // provided: 사건 결제가 이 값을 하나라도 보냈는지 — false면 비교하지 않았다는 뜻이다 ("정보 없음", 일치 없음과 다르다)
    // byCasePayment: 사건 결제 번호 → 그 결제와 같은 값인 후보 결제 번호. 일치가 있는 사건 결제만 담는다
    //   한 번 조회로 짝까지 돌려준다 — 사건 결제마다 따로 물으면 조회가 결제 수만큼 는다 (#818 Codex 보안 리뷰)
    public record Kind(boolean provided, Map<Long, Set<Long>> byCasePayment) {

        public Kind {
            byCasePayment = byCasePayment.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
        }

        public static Kind notProvided() {
            return new Kind(false, Map.of());
        }

        // 일치한 후보 결제 번호 전체
        public Set<Long> paymentIds() {
            return byCasePayment.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        }
    }
}
