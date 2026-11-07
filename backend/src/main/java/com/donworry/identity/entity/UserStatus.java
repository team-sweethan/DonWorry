package com.donworry.identity.entity;

public enum UserStatus {
    ACTIVE,
    // 퇴사 · 운영 정지 — [계정 관리]에서 관리자가 켜고 끈다
    INACTIVE,
    // 보안 정지(조사 중) — 내부자 조사 판정으로만 풀린다 (ADR-0084). 정지 기록은 staff_account_locks
    SUSPENDED
}
