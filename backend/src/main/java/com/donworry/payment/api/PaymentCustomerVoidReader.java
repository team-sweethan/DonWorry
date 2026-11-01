package com.donworry.payment.api;

import java.util.Optional;

// 내부 조회 전용. 호출자가 접근 권한을 확인한 가맹점과 결제 ID를 전달한다.
public interface PaymentCustomerVoidReader {
    // empty는 소유 결제에 고객 VOID 성공이 없다는 뜻이다. 직원 VOID와 REFUND는 포함하지 않는다.
    // 잘못된 ID·없는 결제·소유 불일치는 IllegalArgumentException, 원본 불일치는 IllegalStateException.
    // DB 오류는 전파한다. 호출자 트랜잭션과 분리된 최신 커밋 스냅샷이며 금융 실행 잠금은 아니다.
    Optional<PaymentCustomerVoidSummary> findSucceeded(Long merchantId, Long paymentId);
}
