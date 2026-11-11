package com.donworry.investigation.report.entity;

// 내부자 해제 검토 보고서를 만든 계기 (#675) — 사건당 계기별 한 판
public enum InsiderReportTrigger {
    // 높음 이상 경보로 내부자 사건이 열림
    CASE_OPENED("사건 생성"),
    // 높음 이상 경보 중 내보내기 시도 → 차단 · 계정 정지 (INS-05)
    EXPORT_BLOCKED("내보내기 차단");

    private final String label;

    InsiderReportTrigger(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
