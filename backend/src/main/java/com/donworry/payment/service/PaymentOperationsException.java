package com.donworry.payment.service;

public class PaymentOperationsException extends RuntimeException {
    public PaymentOperationsException() {
        super("결제를 찾을 수 없습니다.");
    }
}
