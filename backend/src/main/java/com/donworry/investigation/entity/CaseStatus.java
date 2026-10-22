package com.donworry.investigation.entity;

// Case 상태 (fds 정본 03 §3.3 ③)
public enum CaseStatus {
    OPEN,
    INVESTIGATING,
    DECISION_PENDING,
    ACTION_PENDING,
    ACTION_FAILED,
    CLOSED
}
