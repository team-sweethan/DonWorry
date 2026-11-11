package com.donworry.investigation.dto;

import com.donworry.investigation.entity.AssignmentChangeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// ADMIN 담당 변경 (#608). 사유가 OTHER면 memo 필수
public record CaseReassignRequest(
        @NotNull Long newStaffId,
        @NotNull AssignmentChangeReason reason,
        @Size(max = 200) String memo) {}
