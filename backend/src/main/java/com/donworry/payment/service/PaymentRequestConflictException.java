package com.donworry.payment.service;

import lombok.Getter;

// P5 내부 업무 충돌이다. HTTP 상태/공통 오류 응답 매핑은 HTTP 단계에서 연결한다.
@Getter
public class PaymentRequestConflictException extends RuntimeException {
    public enum Code {
        IDEMPOTENCY_KEY_CONFLICT,
        ORDER_CONTENT_CONFLICT,
        ORDER_PAYMENT_EXISTS,
        ORDER_DEADLINE_PASSED,
        LEGACY_ORDER_EXISTS
    }

    private final Code code;

    public PaymentRequestConflictException(Code code) {
        super(code.name());
        this.code = code;
    }
}
