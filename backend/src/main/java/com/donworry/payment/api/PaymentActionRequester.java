package com.donworry.payment.api;

// P3가 권한·Case 연결·판정을 검증하고 판정 커밋 후 호출할 공개 계약이다.
// 실행 연결은 후속 단계이며, 저장만 하고 PENDING을 반환하는 구현 Bean은 등록하지 않는다.
public interface PaymentActionRequester {
    PaymentActionReceipt request(PaymentActionCommand command);
}
