package com.donworry.investigation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// ADMIN 검토 결과 (#619) — APPROPRIATE(적절) · EXPLANATION_REQUESTED(소명 요청)
public record RawViewReviewRequest(
        @NotBlank String result, @Size(max = 200) String memo) {}
