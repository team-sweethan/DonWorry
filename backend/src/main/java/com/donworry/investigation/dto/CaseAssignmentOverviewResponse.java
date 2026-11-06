package com.donworry.investigation.dto;

import java.util.List;
import lombok.Builder;

// ADMIN 담당 현황 (#608) — 직원별 미종결 담당 수와 다음 자동 배정 순서
public record CaseAssignmentOverviewResponse(
        List<StaffLoadResponse> staff, long unassignedOpenCount, long pendingRequestCount) {

    // assignOrder: 다음 자동 배정을 받을 순서(1부터). 정지 직원은 null
    @Builder
    public record StaffLoadResponse(
            Long staffId, String displayName, boolean active, long openCount, Integer assignOrder) {}
}
