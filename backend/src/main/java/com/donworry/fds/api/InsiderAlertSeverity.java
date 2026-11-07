package com.donworry.fds.api;

// 내부자 경보 등급 (#516) — 선언 순서가 낮음 → 높음이다
// 주의 = 관리자 경보만 / 높음 = + InsiderAlertRaised (내부자 사건, 조각 8) / 위험 = 이미 계정 정지까지 한 경보 (INS-05, #651)
public enum InsiderAlertSeverity {
    CAUTION,
    HIGH,
    CRITICAL;

    public boolean isHigherThan(InsiderAlertSeverity other) {
        return compareTo(other) > 0;
    }

    public boolean isRaisedToOthers() {
        return this != CAUTION;
    }
}
