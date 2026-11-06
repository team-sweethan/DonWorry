package com.donworry.investigation.dto;

import com.donworry.investigation.entity.AssignmentChangeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 담당 직원의 담당 변경 요청 (#608). 사유가 OTHER면 memo 필수 — 서비스가 확인한다. 개인정보는 적지 않는다(화면 안내)
public record AssignmentChangeSubmitRequest(
        @NotNull AssignmentChangeReason reason,
        @Size(max = 200) String memo) {}
