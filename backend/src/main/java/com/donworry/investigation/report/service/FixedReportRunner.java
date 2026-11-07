package com.donworry.investigation.report.service;

import com.donworry.investigation.report.client.AiServerProperties;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.repository.AiReportRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 고정 문장 보고서로 같은 버전을 끝낸다 — AI를 쓰지 않을 때(설정 꺼짐 · 긴급정지)의 백업모형 (ADR-0083 결정 4)
// 새 요청(AiReportService)과 AI 경로의 재시도 · 결과 반영(AiInvestigationRunner)이 함께 쓴다 — 순환을 피하려고 따로 둔다
// 조회 오류는 최대 3회 다시 시도하고, 모두 실패하면 FAILED로 끝낸다
// 결제 근거가 빠진 경우는 다시 해도 같으므로 바로 FAILED — 직원은 수동 판정으로 간다 (fds 정본 02 §2.5)
@Slf4j
@Component
@RequiredArgsConstructor
class FixedReportRunner {

    private final AiReportRepository aiReportRepository;
    private final AiReportWriter aiReportWriter;
    private final FixedReportGenerator fixedReportGenerator;
    private final AiServerProperties aiServerProperties;

    // 이 호출이 보고서를 끝냈으면 true — 이미 끝난 보고서는 만들지도 바꾸지도 않는다
    boolean run(AiReport report) {
        if (aiReportRepository
                .findById(report.getId())
                .filter(r -> !r.isFinished())
                .isEmpty()) {
            return false;
        }
        // 고정 보고서는 AI를 쓸 수 없을 때만 만든다 — 설정이 켜져 있는데 여기 왔으면 스위치가 꺼진 것이다
        FixedReportReason reason =
                aiServerProperties.enabled() ? FixedReportReason.ADMIN_STOPPED : FixedReportReason.CONFIG_OFF;
        String lastError = "UNKNOWN";
        for (int attempt = 1; attempt <= AiReportService.MAX_ATTEMPTS; attempt++) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            try {
                Optional<Map<String, Object>> body = fixedReportGenerator.generate(
                        report.getCaseId(), report.getReportVersion(), report.getEvidenceVersion(), now, reason);
                if (body.isEmpty()) {
                    return aiReportWriter.fail(
                            report.getId(),
                            AiReportWriter.error(
                                    "EVIDENCE_MISSING", "연결 결제의 금액 · 요청 시각 · FDS 점수 중 빠진 값이 있음", attempt, now),
                            now);
                }
                return aiReportWriter.succeed(report.getId(), body.get(), now);
            } catch (RuntimeException e) {
                lastError = e.getClass().getSimpleName();
                log.warn("보고서 생성 시도 실패 reportId={} attempt={}", report.getId(), attempt, e);
            }
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return aiReportWriter.fail(
                report.getId(),
                AiReportWriter.error("GENERATION_ERROR", lastError, AiReportService.MAX_ATTEMPTS, now),
                now);
    }
}
