package com.donworry.payment.service;

// 담당 사건 범위 밖 고객의 거래 상세 (#732, ADR-0082 3.3) — 거부 기록은 이미 남겼다
public class TransactionDetailOutOfScopeException extends RuntimeException {
    public TransactionDetailOutOfScopeException() {
        super("transaction detail out of case scope");
    }
}
