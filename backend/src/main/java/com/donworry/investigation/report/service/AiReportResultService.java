package com.donworry.investigation.report.service;

import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.client.AiServerProperties;
import com.donworry.investigation.report.dto.AiInvestigationResult;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.AiReportRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

// ai-server 결과 반영 — 콜백 · 마감 초과 조회 · 재시작 복구가 모두 여기로 모인다 (ADR-0009 결정 2)
// 현재 시도 토큰과 같은 결과만, 끝나지 않은 보고서에만 반영한다
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportResultService {

    // 다시 해도 같은 결과라 재시도하지 않는 실패 — 근거 누락 · 조사 중 증거 버전 변경 · 결제 없음
    static final Set<String> FINAL_FAILURES = Set.of("EVIDENCE_MISSING", "EVIDENCE_STALE", "NO_PAYMENTS");

    private final AiReportRepository aiReportRepository;
    private final AiAttemptTracker tracker;
    private final AiInvestigationRunner runner;
    private final ReportBodyValidator validator;
    private final AiServerClient aiServerClient;
    private final AiServerProperties properties;

    public void receive(Long reportId, AiInvestigationResult result) {
        // 진행 중인 시도가 없는 보고서는 잠금을 만들기 전에 무시한다 — 없는 ID로 반복해 보내도 잠금이 쌓이지 않게
        if (tracker.current(reportId).isEmpty()) {
            log.info("진행 중인 시도가 없는 결과를 무시 reportId={} attemptId={}", reportId, result.attemptId());
            return;
        }
        synchronized (tracker.lockFor(reportId)) {
            boolean current = tracker.current(reportId)
                    .map(attempt -> attempt.attemptId().equals(result.attemptId()))
                    .orElse(false);
            if (!current) {
                log.info("현재 시도가 아닌 결과를 무시 reportId={} attemptId={}", reportId, result.attemptId());
                return;
            }
            Optional<AiReport> report = aiReportRepository.findById(reportId).filter(r -> !r.isFinished());
            if (report.isEmpty()) {
                tracker.remove(reportId);
                return;
            }
            apply(report.get(), result);
        }
    }

    // 결과 마감을 넘긴 시도 — ai-server에 상태를 묻고, 끝났으면 반영 · 아니면 실패 1회
    void checkOverdue(Instant now) {
        for (Long reportId : tracker.overdue(now)) {
            synchronized (tracker.lockFor(reportId)) {
                var attempt = tracker.current(reportId).filter(a -> a.deadline().isBefore(now));
                Optional<AiReport> report =
                        aiReportRepository.findById(reportId).filter(r -> !r.isFinished());
                if (report.isEmpty()) {
                    tracker.remove(reportId);
                    continue;
                }
                if (attempt.isEmpty()) {
                    continue;
                }
                Optional<AiInvestigationResult> status = safeStatus(reportId)
                        .filter(s -> attempt.get().attemptId().equals(s.attemptId()))
                        .filter(AiInvestigationResult::isFinished);
                if (status.isPresent()) {
                    apply(report.get(), status.get());
                } else {
                    runner.retryOrFail(report.get(), "RESULT_TIMEOUT", "결과 마감 안에 조사가 끝나지 않음");
                }
            }
        }
    }

    // 재시작 복구 — RUNNING이면 ai-server에 남은 결과부터 확인하고, 없으면 처음부터 다시 맡긴다
    void recover(AiReport report) {
        if (report.getStatus() == ReportStatus.RUNNING) {
            Optional<AiInvestigationResult> status = safeStatus(report.getId());
            if (status.isPresent() && status.get().isFinished()) {
                synchronized (tracker.lockFor(report.getId())) {
                    tracker.adopt(
                            report.getId(),
                            status.get().attemptId(),
                            Instant.now().plus(properties.resultTimeout()));
                    apply(report, status.get());
                }
                return;
            }
        }
        runner.start(report);
    }

    private void apply(AiReport report, AiInvestigationResult result) {
        switch (result.status()) {
            case "SUCCEEDED" -> {
                List<String> errors = validator.validate(report, result.reportBody());
                if (errors.isEmpty()) {
                    runner.finishSucceeded(report, result.reportBody());
                } else {
                    runner.retryOrFail(report, "REPORT_INVALID", String.join("; ", errors));
                }
            }
            case "FAILED" -> {
                if (FINAL_FAILURES.contains(result.errorCode())) {
                    runner.finishFailed(report, result.errorCode(), result.errorReason());
                } else {
                    runner.retryOrFail(report, result.errorCode(), result.errorReason());
                }
            }
            default -> log.debug("아직 진행 중 reportId={}", report.getId());
        }
    }

    private Optional<AiInvestigationResult> safeStatus(Long reportId) {
        try {
            return aiServerClient.status(reportId);
        } catch (RestClientException e) {
            log.warn("ai-server 상태 조회 실패 reportId={}", reportId, e);
            return Optional.empty();
        }
    }
}
