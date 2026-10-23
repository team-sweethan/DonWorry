package com.donworry.investigation.dto;

import java.time.Instant;

// Case 목록 한 줄 — 화면 CaseSummary와 같은 모양
public record CaseSummaryResponse(
        Long caseId,
        Long merchantId,
        String customerRef,
        String caseType,
        String status,
        int evidenceVersion,
        Instant createdAt,
        Instant closedAt,
        int paymentCount,
        long totalAmount,
        Integer maxRiskScore,
        String latestReportStatus,
        int decidedCount) {}
