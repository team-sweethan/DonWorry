package com.donworry.investigation.dto;

import lombok.Builder;

// 내부자 관제 왼쪽 직원 한 줄 (#679, ADR-0082 조각 10)
@Builder
public record InsiderMonitorStaffResponse(
        Long staffId,
        String staffName,
        InsiderMonitorStage stage,
        long openAlertCount,
        Long openInsiderCaseId,
        boolean locked) {}
