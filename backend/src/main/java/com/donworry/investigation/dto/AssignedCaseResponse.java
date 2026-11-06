package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// ADMIN 직원 사건 목록 한 줄 (#608). 고객 식별자 · 결제 · 보고서는 넣지 않는다 — ADMIN은 FDS 사건 상세를 보지 않는다 (ADR-0065)
@Builder
public record AssignedCaseResponse(
        Long caseId,
        Long merchantId,
        String merchantName,
        String status,
        Instant createdAt,
        boolean assignmentRequestPending) {}
