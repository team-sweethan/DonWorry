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
    TRANSACTION_LIST_VIEW,
    // 사건 결제 1건의 주문자 정보 (#619) — 가린 값 열람 · 번호 대조 일치/불일치 · 전화 원문 열람. 입력 번호 · 원문은 남기지 않는다
    // CONTACT_MATCH는 거부된 대조 시도만 (허용이면 결과에 따라 HIT · MISS)
    CONTACT_MASKED_VIEW,
    CONTACT_MATCH,
    CONTACT_MATCH_HIT,
    CONTACT_MATCH_MISS,
    CONTACT_RAW_VIEW
}
