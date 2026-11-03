package com.donworry.identity.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AccountManagementException extends RuntimeException {
    private final AuthErrorCode errorCode;
}
