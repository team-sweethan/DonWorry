package com.donworry.payment.service;

import lombok.Getter;

@Getter
public class PaymentFdsDeliveryNotAllowedException extends RuntimeException {
    public enum Reason {
        RECOVERY_DATA_MISSING,
        DELIVERY_RECORD_UNAVAILABLE
    }

    private final Reason reason;

    public PaymentFdsDeliveryNotAllowedException(Reason reason) {
        super("현재 기록으로 전달을 복구할 수 없습니다.");
        this.reason = reason;
    }
}
