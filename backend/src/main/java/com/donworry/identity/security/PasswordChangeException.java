package com.donworry.identity.security;

import lombok.Getter;

@Getter
public class PasswordChangeException extends RuntimeException {
    private final AuthErrorCode errorCode;

    public PasswordChangeException(AuthErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
