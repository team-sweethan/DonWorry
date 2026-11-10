package com.donworry.identity.api;

import java.time.Duration;

// 로그인 공격 경계 기준 (#770) — 최근 window 안 전체 로그인 시도가 minAttempts 이상이고 실패 비율이 failureRatio 이상이면 경계
// 탐지(LoginAttackCheck)와 대시보드가 같은 값을 쓴다. 바꿀 때는 application.yml security.login-attack.*
public record LoginAttackCriteria(Duration window, long minAttempts, double failureRatio) {
    public boolean isUnderAttack(long attempts, long failures) {
        return attempts >= minAttempts && attempts > 0 && (double) failures / attempts >= failureRatio;
    }
}
