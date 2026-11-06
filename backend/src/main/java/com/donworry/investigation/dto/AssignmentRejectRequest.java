package com.donworry.investigation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// ADMIN 담당 변경 요청 거절 — 요청한 직원이 사건 이력에서 이유를 보므로 memo 필수 (#608)
public record AssignmentRejectRequest(
        @NotBlank @Size(max = 200) String memo) {}
