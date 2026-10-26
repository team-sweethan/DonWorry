package com.donworry.investigation.entity;

// 금융 조치 인계 상태 (fds 정본 03 §3.3 ⑦)
// 완료 기반 (ADR-0027) — ACCEPTED는 실제 매입 · 취소 완료, FAILED는 확정 거절, REQUESTED는 처리 대기 중이다
public enum HandoffStatus {
    REQUESTED,
    ACCEPTED,
    FAILED
}
