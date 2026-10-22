package com.donworry.investigation.entity;

// Case 감사 이력 종류 (fds 정본 03 §3.9 ⑧)
public enum CaseHistoryEventType {
    CASE_CREATED,
    PAYMENT_LINKED,
    DECISION_CONFIRMED,
    HANDOFF_REQUESTED,
    HANDOFF_ACCEPTED,
    HANDOFF_FAILED,
    // CASE_CREATED · CASE_CLOSED 외의 상태 전이 — event_data에 from · to를 남긴다
    CASE_STATUS_CHANGED,
    CASE_CLOSED
}
