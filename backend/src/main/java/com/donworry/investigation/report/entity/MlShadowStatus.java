package com.donworry.investigation.report.entity;

// ML Shadow 채점 기록 상태 (설계 375 8절) — SUCCEEDED · SKIPPED는 끝, FAILED는 최대 시도 수까지 다시 한다
public enum MlShadowStatus {
    SUCCEEDED,
    FAILED,
    SKIPPED
}
