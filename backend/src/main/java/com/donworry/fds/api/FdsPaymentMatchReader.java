package com.donworry.fds.api;

import java.util.Collection;

// 넘겨받은 후보 결제 중 사건 결제와 같은 카드 · 기기 · IP를 쓴 것 (#821, ADR-0034)
// 돌려주는 결제 번호는 candidatePaymentIds의 일부뿐이다 — 받는 쪽이 모르던 결제 번호 · 고객 번호 · 원문 식별자는 나가지 않는다
// 후보는 받는 쪽이 정한다 (P4: 같은 가맹점 다른 고객의 종료 사건 결제). 비교는 같은 가맹점 평가끼리만 한다
// FdsLinkedPaymentReader(다른 고객 결제 집계)와 뜻이 달라 인터페이스를 나눈다
public interface FdsPaymentMatchReader {

    // 한 번에 받는 후보 상한 — 넘으면 IllegalArgumentException
    int MAX_CANDIDATES = 500;

    FdsPaymentMatches findMatching(
            Long merchantId, Collection<Long> casePaymentIds, Collection<Long> candidatePaymentIds);
}
