package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// ADMIN 담당 변경 요청 목록 한 줄 (#608). 사건은 번호 · 가맹점 · 상태만 (ADR-0065)
@Builder
public record AssignmentRequestResponse(
        Long requestId,
        Long caseId,
        Long merchantId,
        String merchantName,
        String caseStatus,
        Long currentAssigneeStaffId,
        String currentAssigneeName,
        Long requestedByStaffId,
        String requestedByName,
        String reason,
        String memo,
        String status,
        Instant requestedAt,
        String reviewedByName,
        Instant reviewedAt,
        String reviewMemo,
        Long newAssigneeStaffId,
        String newAssigneeName) {}
