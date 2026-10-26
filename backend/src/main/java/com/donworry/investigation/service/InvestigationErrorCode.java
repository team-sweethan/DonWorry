package com.donworry.investigation.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 공통 ErrorCode 위치가 정해지기 전까지 모듈 안에 둔다 (02-code 3절 보류)
@Getter
@RequiredArgsConstructor
public enum InvestigationErrorCode {
    CASE_NOT_FOUND("INV_001", "Case가 없습니다.", HttpStatus.NOT_FOUND),
    CASE_CLOSED("INV_002", "이미 종료된 Case입니다.", HttpStatus.CONFLICT),
    EVIDENCE_VERSION_CONFLICT("INV_003", "Case에 새 결제가 추가됐습니다. 최신 내용을 확인한 뒤 다시 판정해 주세요.", HttpStatus.CONFLICT),
    REPORT_NOT_LATEST("INV_004", "최신 AI 보고서가 아닙니다. 최신 보고서를 확인한 뒤 다시 판정해 주세요.", HttpStatus.CONFLICT),
    MANUAL_DECISION_NOT_ALLOWED("INV_005", "AI 보고서가 최종 실패한 경우에만 보고서 없이 판정할 수 있습니다.", HttpStatus.BAD_REQUEST),
    PAYMENT_NOT_IN_CASE("INV_006", "이 Case에 속한 결제가 아닙니다.", HttpStatus.BAD_REQUEST),
    PAYMENT_ALREADY_DECIDED("INV_007", "이미 판정한 결제입니다.", HttpStatus.CONFLICT),
    DUPLICATE_PAYMENT_IN_REQUEST("INV_008", "같은 결제를 한 요청에서 두 번 판정할 수 없습니다.", HttpStatus.BAD_REQUEST),
    SECURITY_STAFF_ONLY("INV_009", "보안 직원만 처리할 수 있습니다.", HttpStatus.FORBIDDEN),
    LOGIN_REQUIRED("INV_010", "로그인이 필요합니다.", HttpStatus.UNAUTHORIZED),
    HANDOFF_NOT_FOUND("INV_011", "이 Case의 금융 조치 인계가 아닙니다.", HttpStatus.NOT_FOUND),
    HANDOFF_NOT_PENDING("INV_012", "금융 처리 대기 중인 인계만 다시 확인할 수 있습니다.", HttpStatus.CONFLICT),
    PAYMENT_ACTION_UNAVAILABLE("INV_013", "금융 처리 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
