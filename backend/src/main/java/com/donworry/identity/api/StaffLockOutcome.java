package com.donworry.identity.api;

// 정지를 풀 때 계정을 어디로 돌릴지 — 판정이 고른다 (ADR-0084)
public enum StaffLockOutcome {
    // 무혐의 — 사용 중(ACTIVE)으로 복귀
    RESTORE,
    // 징계 · 퇴사 — 비활성(INACTIVE)으로. 풀리는 순간에도 로그인할 수 없다
    DEACTIVATE
}
