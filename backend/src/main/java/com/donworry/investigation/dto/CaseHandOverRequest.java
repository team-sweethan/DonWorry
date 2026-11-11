package com.donworry.investigation.dto;

import com.donworry.investigation.entity.AssignmentChangeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// ADMIN [모두 다른 직원에게 넘기기] (#608). 사유가 OTHER면 memo 필수
public record CaseHandOverRequest(
        @NotNull AssignmentChangeReason reason,
        @Size(max = 200) String memo) {}
