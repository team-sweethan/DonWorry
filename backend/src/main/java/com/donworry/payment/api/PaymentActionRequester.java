package com.donworry.payment.api;

// P3가 권한·Case 연결·판정을 검증하고 판정 커밋 후 호출할 공개 계약이다.
// CAPTURE 실행을 연결했다. VOID는 후속 단계이며 아직 기술 예외로 거절한다.
public interface PaymentActionRequester {
    PaymentActionReceipt request(PaymentActionCommand command);
}
