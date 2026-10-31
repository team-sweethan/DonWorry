package com.donworry.payment.service;

import com.donworry.payment.api.PaymentCustomerVoidSucceeded;

// P5 내부 발행 경계. P3 운영 수신·복구 계약을 연결한 뒤에만 운영 Bean을 등록한다.
// 현재 운영 구현은 없으며 테스트에서만 Modulith 발행 어댑터를 제공한다.
interface PaymentCustomerVoidCompletion {
    void publish(PaymentCustomerVoidSucceeded event);
}
