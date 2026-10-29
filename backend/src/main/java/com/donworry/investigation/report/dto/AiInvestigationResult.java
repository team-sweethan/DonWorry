package com.donworry.investigation.report.dto;

import java.util.List;
import java.util.Map;

// ai-server 상태 조회 응답이자 결과 콜백 본문 (ai-server app/schemas/investigation.py와 같게 둔다)
// status: RUNNING · SUCCEEDED · FAILED. 실패면 reportBody 없이 error(code · reason)만 온다
// progress는 RUNNING일 때 지금까지 끝난 단계 (#322) — 화면 진행 표시에만 쓰고 결과 반영에는 쓰지 않는다
public record AiInvestigationResult(
        String reportId,
        String attemptId,
        String status,
        Map<String, Object> reportBody,
        Map<String, String> error,
        List<AiTraceStep> progress) {

    // 진행 기록 없이 만드는 결과 (콜백 · 테스트)
    public AiInvestigationResult(
            String reportId,
            String attemptId,
            String status,
            Map<String, Object> reportBody,
            Map<String, String> error) {
        this(reportId, attemptId, status, reportBody, error, null);
    }

    public boolean isFinished() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }

    public String errorCode() {
        return error == null ? "UNKNOWN" : error.getOrDefault("code", "UNKNOWN");
    }

    public String errorReason() {
        return error == null ? "" : error.getOrDefault("reason", "");
    }
}
