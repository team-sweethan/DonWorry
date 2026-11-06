package com.donworry.identity.api;

// 거부 이유. 값은 거부를 기록하는 조각에서 더한다
public enum StaffAccessDenyReason {
    NOT_ASSIGNEE,
    // 주문자 정보는 열린 사건에서만 (#619)
    CASE_CLOSED,
    // 내 사건 번호에 사건 밖 결제 번호를 넣은 시도 (#619)
    PAYMENT_NOT_IN_CASE
}
