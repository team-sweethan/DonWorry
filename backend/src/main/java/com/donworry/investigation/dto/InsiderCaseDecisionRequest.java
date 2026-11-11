package com.donworry.investigation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// ADMIN 내부자 사건 판정 (#670) — NORMAL_DUTY(정상 업무) · CAUTION_NOTICE(주의 조치) · DISCIPLINE_REQUEST(징계 요청)
public record InsiderCaseDecisionRequest(
        @NotBlank String decision, @Size(max = 200) String memo) {}
