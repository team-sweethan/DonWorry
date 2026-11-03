package com.donworry.investigation.service;

import lombok.Getter;

@Getter
public class InvestigationException extends RuntimeException {

    private final InvestigationErrorCode errorCode;

    public InvestigationException(InvestigationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
