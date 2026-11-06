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
    PAYMENT_ACTION_UNAVAILABLE("INV_013", "금융 처리 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    MERCHANT_VIEWER_ONLY("INV_014", "조치 결과를 조회할 권한이 없습니다.", HttpStatus.FORBIDDEN),
    PAYMENT_IDS_OUT_OF_RANGE("INV_015", "결제 ID는 1개 이상 100개 이하로 보내 주세요.", HttpStatus.BAD_REQUEST),
    PAYMENT_CANCELED_BY_CUSTOMER("INV_016", "고객이 승인을 취소한 결제라 판정할 수 없습니다.", HttpStatus.CONFLICT),
    // 사건 담당 배정 · 변경 (#608)
    ADMIN_ONLY("INV_017", "관리자만 처리할 수 있습니다.", HttpStatus.FORBIDDEN),
    NOT_CASE_ASSIGNEE("INV_018", "이 사건의 담당자만 담당 변경을 요청할 수 있습니다.", HttpStatus.FORBIDDEN),
    ASSIGNMENT_REQUEST_PENDING("INV_019", "이미 처리를 기다리는 담당 변경 요청이 있습니다.", HttpStatus.CONFLICT),
    ASSIGNMENT_CHANGE_PENDING("INV_020", "담당 변경 요청을 처리하는 중이라 판정할 수 없습니다.", HttpStatus.CONFLICT),
    ASSIGNMENT_REQUEST_NOT_FOUND("INV_021", "담당 변경 요청이 없습니다.", HttpStatus.NOT_FOUND),
    ASSIGNMENT_REQUEST_NOT_PENDING("INV_022", "이미 처리된 담당 변경 요청입니다.", HttpStatus.CONFLICT),
    INVALID_ASSIGNEE("INV_023", "담당자로 지정할 수 없는 직원입니다.", HttpStatus.BAD_REQUEST),
    SAME_ASSIGNEE("INV_024", "지금 담당자와 같은 직원입니다. 다른 직원을 골라 주세요.", HttpStatus.BAD_REQUEST),
    NO_AVAILABLE_STAFF("INV_025", "사건을 넘겨받을 수 있는 보안 직원이 없습니다.", HttpStatus.CONFLICT),
    ASSIGNMENT_MEMO_REQUIRED("INV_026", "사유가 기타이면 메모를 적어 주세요.", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST_STATUS("INV_027", "요청 상태 값을 확인해 주세요.", HttpStatus.BAD_REQUEST),
    // 열린 사건은 담당자만 (#612)
    CASE_ASSIGNEE_ONLY("INV_028", "진행 중인 사건은 담당자만 열람 · 처리할 수 있습니다.", HttpStatus.FORBIDDEN),
    // 사건 결제의 주문자 정보 (#619)
    CONTACT_CASE_CLOSED("INV_029", "종료된 사건은 주문자 정보를 볼 수 없습니다.", HttpStatus.CONFLICT),
    CONTACT_NOT_FOUND("INV_030", "이 결제에는 저장된 주문자 정보가 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_PHONE("INV_031", "전화번호 형식을 확인해 주세요.", HttpStatus.BAD_REQUEST),
    RAW_VIEW_REVIEW_NOT_FOUND("INV_032", "원문 열람 기록이 없습니다.", HttpStatus.NOT_FOUND),
    RAW_VIEW_REVIEW_DONE("INV_033", "이미 검토한 원문 열람입니다.", HttpStatus.CONFLICT),
    INVALID_REVIEW_RESULT("INV_034", "검토 결과 값을 확인해 주세요.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
