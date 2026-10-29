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
    UNAVAILABLE("APPLICATION_008", "신청 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    NOT_FOUND("APPLICATION_009", "이용 신청을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    REVIEW_CONFLICT("APPLICATION_010", "검토 대기 중인 신청만 처리할 수 있습니다. 최신 상태를 확인해 주세요.", HttpStatus.CONFLICT),
    INVALID_SETUP_TOKEN("APPLICATION_011", "사용할 수 없는 비밀번호 설정 링크입니다. 승인 안내 메일의 링크를 확인해 주세요.", HttpStatus.BAD_REQUEST),
    EXPIRED_SETUP_TOKEN("APPLICATION_012", "비밀번호 설정 링크가 만료되었습니다. 관리자에게 새 안내 메일을 요청해 주세요.", HttpStatus.GONE),
    USED_SETUP_TOKEN("APPLICATION_013", "이미 비밀번호를 설정한 링크입니다. 설정한 비밀번호로 로그인해 주세요.", HttpStatus.CONFLICT),
    REVOKED_SETUP_TOKEN("APPLICATION_014", "더 이상 사용할 수 없는 링크입니다. 최신 안내 메일을 확인하거나 관리자에게 문의해 주세요.", HttpStatus.GONE),
    SETUP_UNAVAILABLE("APPLICATION_015", "현재 이 계정의 비밀번호를 설정할 수 없습니다. 관리자에게 계정·가맹점 상태를 문의해 주세요.", HttpStatus.CONFLICT),
    INVALID_REVIEW_INPUT("APPLICATION_016", "승인 정보 또는 신청자에게 전달할 반려 사유를 확인해 주세요.", HttpStatus.BAD_REQUEST),
    INVALID_SETUP_INPUT("APPLICATION_017", "메일의 비밀번호 설정 링크와 새 비밀번호를 확인해 주세요.", HttpStatus.BAD_REQUEST),
    LOGIN_ID_UNAVAILABLE("APPLICATION_018", "이미 사용 중이거나 다른 신청에서 선택한 로그인 아이디입니다. 다른 아이디를 입력해 주세요.", HttpStatus.CONFLICT),
    LOGIN_ID_REQUIRED(
            "APPLICATION_019", "이전 양식으로 접수되어 로그인 아이디가 없습니다. 신청자에게 새 양식으로 재신청하도록 안내해 주세요.", HttpStatus.CONFLICT);
    private final String code;
    private final String message;
    private final HttpStatus status;
}
