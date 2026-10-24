package com.donworry.identity.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode {
    AUTHENTICATION_REQUIRED("AUTH_001", "로그인이 필요합니다.", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("AUTH_002", "권한이 없습니다.", HttpStatus.FORBIDDEN),
    CURRENT_PASSWORD_MISMATCH("AUTH_003", "현재 비밀번호가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_NEW_PASSWORD(
            "AUTH_006", "새 비밀번호는 8~64자, UTF-8 72바이트 이하로 영문과 숫자를 포함하고 아이디와 달라야 합니다.", HttpStatus.BAD_REQUEST),
    INVALID_CREDENTIALS("AUTH_004", "아이디 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),
    AUTHENTICATION_UNAVAILABLE("AUTH_005", "로그인을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    PROCESSING_UNAVAILABLE("AUTH_007", "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
