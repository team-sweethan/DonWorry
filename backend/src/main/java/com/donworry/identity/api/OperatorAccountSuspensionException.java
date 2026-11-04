package com.donworry.identity.api;

import lombok.Getter;

@Getter
public class OperatorAccountSuspensionException extends RuntimeException {
    public enum Reason {
        USER_NOT_FOUND,
        NOT_OPERATOR
    }

    private final Reason reason;

    public OperatorAccountSuspensionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
