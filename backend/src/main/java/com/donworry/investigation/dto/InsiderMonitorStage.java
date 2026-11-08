package com.donworry.investigation.dto;

// 내부자 관제 직원 단계 (#679, ADR-0082 조각 10) — 화면 색: 초록 · 노랑 · 주황 · 빨강
public enum InsiderMonitorStage {
    NORMAL,
    // 열린 경보가 있지만 내부자 사건은 없음 (주의 경보는 사건을 열지 않는다)
    CAUTION,
    INSIDER_CASE,
    // 정지가 가장 앞선다 — 사건 없이 INS-05로 바로 정지될 수 있다
    BLOCKED
}
