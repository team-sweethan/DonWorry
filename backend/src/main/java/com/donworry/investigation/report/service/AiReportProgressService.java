package com.donworry.investigation.report.service;

import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.client.AiServerProperties;
import com.donworry.investigation.report.dto.AiInvestigationResult;
import com.donworry.investigation.report.dto.AiTraceStep;
import com.donworry.investigation.report.dto.ReportProgressResponse;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.AiReportRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

// 생성 중인 보고서의 진행 단계 조회 (#322) — 조회 전용
// ai-server가 끝났다고 답해도 여기서는 보고서를 바꾸지 않는다. 결과 반영은 콜백 · 복구 경로(AiReportResultService)만 한다
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportProgressService {

    private final AiReportRepository aiReportRepository;
    private final AiServerClient aiServerClient;
    private final AiServerProperties properties;
    private final AiAttemptTracker tracker;

    // 이 Case의 보고서가 아니면 빈 값
    public Optional<ReportProgressResponse> progress(Long caseId, Long reportId) {
        return aiReportRepository
                .findById(reportId)
                .filter(report -> report.getCaseId().equals(caseId))
                .map(report -> new ReportProgressResponse(
                        report.getId(), report.getStatus().name(), steps(report)));
    }

    // 고정 보고서(ai-server 꺼짐)는 진행 기록이 없다. 현재 시도의 기록만 보여 준다
    private List<AiTraceStep> steps(AiReport report) {
        if (!properties.enabled() || report.getStatus() != ReportStatus.RUNNING) {
            return List.of();
        }
        Optional<String> attemptId = tracker.current(report.getId()).map(AiAttemptTracker.Attempt::attemptId);
        return safeStatus(report.getId())
                .filter(result -> "RUNNING".equals(result.status()))
                .filter(result -> attemptId.isPresent() && attemptId.get().equals(result.attemptId()))
                .map(AiInvestigationResult::progress)
                .orElse(List.of());
    }

    // ai-server 장애는 화면 진행 표시만 비운다 — 보고서 · 화면 조회를 실패시키지 않는다
    private Optional<AiInvestigationResult> safeStatus(Long reportId) {
        try {
            return aiServerClient.status(reportId);
        } catch (RestClientException e) {
            log.debug("진행 조회 실패 reportId={}", reportId, e);
            return Optional.empty();
        }
    }
}
