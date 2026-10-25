package com.donworry.payment.api;

// P3가 권한·Case 연결·판정을 검증하고 판정 커밋 후 호출할 공개 계약이다.
// CAPTURE는 완료 대기, VOID는 모의 실행 결과를 반환하며 최종 결과는 이벤트로도 전달한다.
public interface PaymentActionRequester {
    PaymentActionReceipt request(PaymentActionCommand command);
}
