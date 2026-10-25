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
    PROCESSING_UNAVAILABLE("AUTH_007", "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    PASSWORD_CHANGE_REQUIRED("AUTH_008", "비밀번호를 먼저 변경해 주세요.", HttpStatus.FORBIDDEN),
    ADMIN_INVALID_INPUT("ADMIN_001", "계정 관리 입력값을 확인해 주세요.", HttpStatus.BAD_REQUEST),
    ADMIN_USER_NOT_FOUND("ADMIN_002", "계정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    ADMIN_LOGIN_ID_CONFLICT("ADMIN_003", "이미 사용 중인 아이디입니다.", HttpStatus.CONFLICT),
    ADMIN_SELF_DISABLE("ADMIN_004", "자기 자신은 정지할 수 없습니다.", HttpStatus.BAD_REQUEST),
    ADMIN_MERCHANT_REQUIRED("ADMIN_005", "사장님 계정에는 사용 중인 가맹점을 지정해 주세요.", HttpStatus.BAD_REQUEST),
    ADMIN_OPERATOR_PERMISSIONS_ONLY("ADMIN_006", "기능 권한은 사장님 계정에만 설정할 수 있습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
