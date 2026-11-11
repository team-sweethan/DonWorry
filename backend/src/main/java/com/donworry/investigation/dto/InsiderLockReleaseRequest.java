package com.donworry.investigation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 징계로 닫힌 사건의 정지 해제 (#670) — outcome: RESTORE(사용 중 복귀) · DEACTIVATE(비활성). 사유는 필수
public record InsiderLockReleaseRequest(
        @NotBlank String outcome, @NotBlank @Size(max = 200) String reason) {}
