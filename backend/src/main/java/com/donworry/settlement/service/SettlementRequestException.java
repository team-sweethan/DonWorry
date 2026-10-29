package com.donworry.settlement.service;

import lombok.Getter;

@Getter
public class SettlementRequestException extends RuntimeException {
    private final int status;
    private final String code;

    public SettlementRequestException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
