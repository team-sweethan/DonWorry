package com.donworry.fds.api;

// 내부자 탐지 규칙 (#516, ADR-0082) — INS-07 · 08 · 09는 6b, INS-05는 7b에서 더한다
public enum InsiderRule {
    INS_01("INS-01", "담당 밖 접근 반복"),
    INS_03("INS-03", "원문 열람 집중"),
    INS_06("INS-06", "경보 겹침");

    private final String code;
    private final String label;

    InsiderRule(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
