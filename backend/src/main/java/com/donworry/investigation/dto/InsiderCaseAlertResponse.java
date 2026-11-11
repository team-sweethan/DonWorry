package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// 내부자 사건에 묶인 경보 한 줄 (#670) — 요약은 건수 · 규칙만
@Builder
public record InsiderCaseAlertResponse(
        Long alertId,
        String ruleCode,
        String ruleLabel,
        String severity,
        String summary,
        Instant raisedAt,
        Instant linkedAt) {}
