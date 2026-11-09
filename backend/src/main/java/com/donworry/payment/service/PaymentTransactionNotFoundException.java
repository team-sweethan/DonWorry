package com.donworry.payment.service;

// 거래 상세 (#732) — 없는 결제 번호. 열람할 대상이 없어 기록도 남기지 않는다
public class PaymentTransactionNotFoundException extends RuntimeException {
    public PaymentTransactionNotFoundException() {
        super("payment transaction not found");
    }
}
