package com.donworry.payment.service;

import lombok.Getter;

@Getter
public class PaymentFdsReevaluationNotAllowedException extends RuntimeException {
    public enum Reason {
        ANALYSIS_PENDING,
        ANALYSIS_NOT_ALLOWED,
        PAYMENT_STATE_CHANGED,
        RECOVERY_DATA_MISSING
    }

    private final Reason reason;

    public PaymentFdsReevaluationNotAllowedException(Reason reason) {
        super("재평가를 접수할 수 없습니다.");
        this.reason = reason;
    }
}
