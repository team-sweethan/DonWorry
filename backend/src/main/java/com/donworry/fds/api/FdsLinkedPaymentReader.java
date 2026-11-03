package com.donworry.fds.api;

import java.time.Instant;
import java.util.Collection;

// 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 조회 (#352) — 일치 비교는 P2 안에서 원문으로 하고 집계만 내보낸다
// merchantId · customerRef는 사건의 가맹점 · 고객, casePaymentIds는 사건 결제 (비교할 값의 출처이자 제외 대상)
// since 이후에 요청된 같은 가맹점 결제만 본다. 같은 고객 · 사건 결제는 세지 않는다
public interface FdsLinkedPaymentReader {

    FdsLinkedPayments findLinked(Long merchantId, String customerRef, Collection<Long> casePaymentIds, Instant since);
}
