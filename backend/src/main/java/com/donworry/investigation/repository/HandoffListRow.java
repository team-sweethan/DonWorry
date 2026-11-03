package com.donworry.investigation.repository;

import com.donworry.investigation.entity.ActionType;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.HandoffStatus;
import java.time.Instant;

// 조치 내역 한 줄 — 인계 하나와 그 판정 · 결제 · Case (#314)
public record HandoffListRow(
        Long handoffId,
        Long caseId,
        Long paymentId,
        Decision decision,
        ActionType actionType,
        HandoffStatus status,
        String lastErrorCode,
        Long staffId,
        Instant decidedAt,
        Instant requestedAt,
        Instant acceptedAt) {}
