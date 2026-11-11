package com.donworry.investigation.dto;

import java.util.List;
import lombok.Builder;

// ADMIN 담당 현황 (#608) — 직원별 미종결 담당 수와 다음 자동 배정 순서
public record CaseAssignmentOverviewResponse(
        List<StaffLoadResponse> staff, long unassignedOpenCount, long pendingRequestCount) {

    // assignOrder: 다음 자동 배정을 받을 순서(1부터). 정지 직원 · 내부자 사건 조사 중인 직원은 null
    // insiderCaseOpen: 열린 내부자 사건이 있어 배정 · 담당 변경 후보에서 빠진 직원 (#670)
    @Builder
    public record StaffLoadResponse(
            Long staffId,
            String displayName,
            boolean active,
            long openCount,
            Integer assignOrder,
            boolean insiderCaseOpen) {}
}
