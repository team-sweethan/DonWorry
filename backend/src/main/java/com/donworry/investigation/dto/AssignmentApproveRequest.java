package com.donworry.investigation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// ADMIN 담당 변경 요청 승인 — 새 담당을 함께 정한다 (#608)
public record AssignmentApproveRequest(
        @NotNull Long newStaffId, @Size(max = 200) String memo) {}
