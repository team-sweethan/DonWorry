package com.donworry.identity.api;

// 보안 정지 이유. DB는 CHECK 없는 VARCHAR(40)라 조각마다 값을 늘린다
public enum StaffLockReason {
    // 열린 내부자 경보 상태에서 거래 내역 내보내기 시도 (ADR-0082 INS-05, 조각 7b)
    INSIDER_EXPORT_BLOCKED,
    // 내부자 사건 판정 중 관리자가 정지 (조각 8)
    INSIDER_CASE_DECISION,
    // 로그인 공격 경계 중 처음 보는 브라우저 로그인 — 탈취 의심이라 바로 정지 (ADR-0082 3.4 INS-10, #790)
    LOGIN_TAKEOVER_SUSPECTED
}
