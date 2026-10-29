package com.donworry.identity.security;

import lombok.*;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ApplicationErrorCode {
    INVALID_INPUT("APPLICATION_001", "신청 정보와 개인정보 동의를 확인해 주세요.", HttpStatus.BAD_REQUEST),
    DUPLICATE(
            "APPLICATION_002",
            "같은 이메일 또는 사업자등록번호로 진행 중인 신청이 있습니다. 이메일 미확인 상태라면 확인 메일을 다시 요청해 주세요.",
            HttpStatus.CONFLICT),
    RATE_LIMITED(
            "APPLICATION_003",
            "요청 횟수를 초과했습니다. 신청은 1시간 5회, 메일은 1분 1회·24시간 5회까지 요청할 수 있습니다.",
            HttpStatus.TOO_MANY_REQUESTS),
    INVALID_TOKEN("APPLICATION_004", "올바르지 않은 확인 링크입니다.", HttpStatus.BAD_REQUEST),
    EXPIRED_TOKEN("APPLICATION_005", "확인 링크가 만료되었습니다. 확인 메일을 다시 요청해 주세요.", HttpStatus.GONE),
    USED_TOKEN("APPLICATION_006", "이미 이메일 확인을 완료한 링크입니다. 관리자 검토를 기다려 주세요.", HttpStatus.CONFLICT),
    REVOKED_TOKEN("APPLICATION_007", "이전 확인 링크입니다. 가장 최근에 받은 메일을 열어 주세요.", HttpStatus.GONE),
    UNAVAILABLE("APPLICATION_008", "신청 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);
    private final String code;
    private final String message;
    private final HttpStatus status;
}
