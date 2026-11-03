package com.donworry.payment.service;

// 외부 호출·독립 저장·금융 부작용이 없는 모의 승인 계약이다.
interface PaymentAuthorizer {
    AuthorizationResult authorize(AuthorizationCommand command);
}
