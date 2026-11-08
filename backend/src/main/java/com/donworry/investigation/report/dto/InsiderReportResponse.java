package com.donworry.investigation.report.dto;

import com.donworry.investigation.report.entity.InsiderReport;
import com.donworry.investigation.report.entity.ReportStatus;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;

// 내부자 사건 해제 검토 보고서 한 판 (#675) — 관리자 화면용. 실패 판은 body 없이 상태만
@Builder
public record InsiderReportResponse(
        Long reportId,
        int reportVersion,
        String trigger,
        String triggerLabel,
        String status,
        Instant createdAt,
        Map<String, Object> body) {

    public static InsiderReportResponse from(InsiderReport report) {
        return InsiderReportResponse.builder()
                .reportId(report.getId())
                .reportVersion(report.getReportVersion())
                .trigger(report.getTriggerType().name())
                .triggerLabel(report.getTriggerType().label())
                .status(report.getStatus().name())
                .createdAt(report.getCreatedAt())
                .body(report.getStatus() == ReportStatus.SUCCEEDED ? report.getReportBody() : null)
                .build();
    }
}
