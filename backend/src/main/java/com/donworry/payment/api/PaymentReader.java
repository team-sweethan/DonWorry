package com.donworry.payment.api;

import java.util.Optional;

// 내부 조회 전용이다. 호출자가 접근 권한·가맹점 범위를 검증한 결제 ID를 전달한다.
public interface PaymentReader {
    // 없는 결제만 empty다. null·0 이하 ID는 IllegalArgumentException, DB 오류는 전파한다.
    Optional<PaymentSummary> findById(Long paymentId);
}
