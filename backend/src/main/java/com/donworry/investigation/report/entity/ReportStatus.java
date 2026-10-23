package com.donworry.investigation.report.entity;

// AI 보고서 상태 (fds 정본 03 §3.3 ④) — SUCCEEDED · FAILED는 끝난 상태
public enum ReportStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
