package com.donworry.fds.dto;

import java.time.Instant;
import lombok.Builder;

// ADMIN 내부자 경보 한 줄 (#516) — 원문 · IP 없음
@Builder
public record InsiderAlertResponse(
        Long alertId,
        Long staffId,
        String staffName,
        String ruleCode,
        String ruleLabel,
        String severity,
        String status,
        String summary,
        long evidenceCount,
        Instant firstEventAt,
        Instant lastEventAt,
        Instant createdAt,
        Instant updatedAt,
        String closedByName,
        Instant closedAt,
        String closeMemo) {}
