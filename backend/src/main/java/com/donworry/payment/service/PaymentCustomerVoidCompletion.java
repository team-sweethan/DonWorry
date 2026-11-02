package com.donworry.payment.service;

import com.donworry.payment.api.PaymentCustomerVoidSucceeded;

// P5 내부 발행 경계. 운영 구현은 PaymentCustomerVoidEventPublisher(#382, P3 수신부 연결 뒤 등록).
// 이 Bean이 없으면 HELD 고객 취소는 CANCELLATION_NOT_READY로 막힌다.
interface PaymentCustomerVoidCompletion {
    void publish(PaymentCustomerVoidSucceeded event);
}
