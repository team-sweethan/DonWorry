package com.donworry.payment.service;

import java.util.Optional;

// P5 내부 어댑터 요구사항이다. P1 공개 계약이 아니며 운영 구현은 아직 제공하지 않는다.
// 구현은 서버 간 인증 결과만 사용해야 한다. 직원 세션·요청 본문의 ID를 대신 사용하면 안 된다.
public interface PaymentMerchantIdentity {
    Optional<Long> currentMerchantId();
}
