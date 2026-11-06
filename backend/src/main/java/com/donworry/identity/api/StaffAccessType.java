package com.donworry.identity.api;

// 직원이 하려던 일. 값은 기록을 부르는 조각에서 더한다 (ADR-0082). DB는 CHECK 없는 VARCHAR라 값 추가에 migration이 필요 없다
public enum StaffAccessType {
    CASE_DETAIL_VIEW,
    // 열린 사건 담당자만 하는 동작 (#612) — 거부만 기록한다
    CASE_DECISION,
    REPORT_REQUEST,
    HANDOFF_RECHECK,
    REPORT_PROGRESS_VIEW,
    // 거래 목록 조회 (#615) — 내보낸 건수 · 거른 가맹점 · 검색한 고객 번호를 남긴다
    TRANSACTION_LIST_VIEW
}
