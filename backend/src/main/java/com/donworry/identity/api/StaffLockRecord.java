package com.donworry.identity.api;

import java.time.Instant;
import lombok.Builder;

// 정지 기록 한 줄. lockedBy가 null이면 시스템 자동 정지다. 열린 정지는 released* · outcome이 null이다
@Builder
public record StaffLockRecord(
        Long lockId,
        Long staffId,
        StaffLockReason reason,
        Long sourceAlertId,
        Long lockedBy,
        Instant lockedAt,
        boolean open,
        Instant releasedAt,
        Long releasedBy,
        Long releaseCaseId,
        String releaseDecision,
        StaffLockOutcome releaseOutcome) {}
