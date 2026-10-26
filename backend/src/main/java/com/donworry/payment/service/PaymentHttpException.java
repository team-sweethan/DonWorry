package com.donworry.payment.service;

import lombok.Getter;

@Getter
public class PaymentHttpException extends RuntimeException {
    private final int status;
    private final String code;

    public PaymentHttpException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
