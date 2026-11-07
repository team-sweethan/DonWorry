package com.donworry.investigation.report.entity;

// AI 조사 긴급정지 스위치 변경 (ADR-0083) — 마지막 기록이 지금 상태다. 기록이 없으면 켜짐
public enum AiSwitchAction {
    STOPPED,
    RESUMED
}
