package com.donworry.investigation.entity;

// 담당 변경 요청 상태 — PENDING에서 APPROVED · REJECTED로 한 번만 바뀐다
public enum AssignmentRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
