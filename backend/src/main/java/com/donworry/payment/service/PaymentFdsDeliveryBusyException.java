package com.donworry.payment.service;

// 전달 복구 HTTP 연결 시 503 FDS_RECOVERY_DELIVERY_UNAVAILABLE로 매핑한다.
public class PaymentFdsDeliveryBusyException extends RuntimeException {
    public PaymentFdsDeliveryBusyException() {
        super("다른 전달 복구 요청이 처리 중입니다.");
    }
}
