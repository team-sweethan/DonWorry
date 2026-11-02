package com.donworry.payment.entity;

// 결제창 세션 상태. 만료는 저장하지 않고 expiresAt으로 판단한다.
public enum PaymentSessionStatus {
    // 결제창이 열릴 수 있고 아직 결제를 요청하지 않음
    OPEN,
    // 결제창에서 결제를 요청해 payments 행과 연결됨
    SUBMITTED
}
