package com.donworry.identity.api;

// 거부 이유. 값은 거부를 기록하는 조각에서 더한다
public enum StaffAccessDenyReason {
    NOT_ASSIGNEE,
    // 주문자 정보는 열린 사건에서만 (#619)
    CASE_CLOSED,
    // 내 사건 번호에 사건 밖 결제 번호를 넣은 시도 (#619)
    PAYMENT_NOT_IN_CASE,
    // 열린 내부자 경보가 있는 상태에서 내보내기 시도 — 계정 보안 정지와 함께 남는다 (INS-05, #651)
    INSIDER_ALERT_OPEN,
    // 거래 내역 내보내기를 담당 사건 범위 밖 고객으로(또는 가맹점 · 고객 조건 없이) 시도 (#730) — INS-01이 고객 단위로 센다
    EXPORT_OUT_OF_SCOPE
}
