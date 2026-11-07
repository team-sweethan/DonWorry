package com.donworry.identity.api;

// 보안 정지 이유. DB는 CHECK 없는 VARCHAR(40)라 조각마다 값을 늘린다
public enum StaffLockReason {
    // 열린 내부자 경보 상태에서 거래 내역 내보내기 시도 (ADR-0082 INS-05, 조각 7b)
    INSIDER_EXPORT_BLOCKED,
    // 내부자 사건 판정 중 관리자가 정지 (조각 8)
    INSIDER_CASE_DECISION
}
