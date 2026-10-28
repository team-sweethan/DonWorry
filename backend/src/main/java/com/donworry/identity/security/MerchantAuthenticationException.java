package com.donworry.identity.security;

import lombok.Getter;

@Getter
public class MerchantAuthenticationException extends RuntimeException {
    private final AuthErrorCode errorCode;

    public MerchantAuthenticationException(AuthErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
