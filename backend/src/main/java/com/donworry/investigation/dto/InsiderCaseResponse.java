package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// ADMIN 내부자 사건 목록 한 줄 (#670) — 원문 · 고객 번호 없음
@Builder
public record InsiderCaseResponse(
        Long insiderCaseId,
        Long staffId,
        String staffName,
        String status,
        String severity,
        long alertCount,
        Instant openedAt,
        Instant updatedAt,
        String decision,
        String decisionLabel,
        String decisionMemo,
        String decidedByName,
        Instant decidedAt,
        Instant lockReleasedAt,
        String lockReleasedByName,
        String lockReleaseReason,
        String lockReleaseOutcome) {}
