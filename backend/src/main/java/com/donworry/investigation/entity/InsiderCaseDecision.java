package com.donworry.investigation.entity;

// 내부자 사건 판정 (#670, ADR-0082) — 정상 업무 · 주의 조치는 정지를 풀고, 징계 요청은 정지를 유지한다
public enum InsiderCaseDecision {
    NORMAL_DUTY("정상 업무", true),
    CAUTION_NOTICE("주의 조치", true),
    DISCIPLINE_REQUEST("징계 요청", false);

    private final String label;
    private final boolean releasesLock;

    InsiderCaseDecision(String label, boolean releasesLock) {
        this.label = label;
        this.releasesLock = releasesLock;
    }

    public String label() {
        return label;
    }

    public boolean releasesLock() {
        return releasesLock;
    }
}
