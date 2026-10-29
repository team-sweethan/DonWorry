package com.donworry.payment.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 내부 조회 전용이다. 호출자가 접근 권한·가맹점 범위를 검증한 결제 ID를 전달한다.
public interface PaymentReader {
    // 없는 결제만 empty다. null·0 이하 ID는 IllegalArgumentException, DB 오류는 전파한다.
    Optional<PaymentSummary> findById(Long paymentId);

    // 같은 가맹점 · 같은 고객의 since 이후 결제를 최근 순으로 최대 limit건 (#338). 호출자가 가맹점 · 고객 범위를 정한다
    // merchantId · customerRef · since가 비었거나 limit이 1~100 밖이면 IllegalArgumentException
    List<PaymentSummary> findRecentByCustomer(Long merchantId, String customerRef, Instant since, int limit);
}
