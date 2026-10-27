package com.donworry.investigation.report.service;

import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.AiReportRepository;
import com.donworry.investigation.service.ReportGateway;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// P3 → P4 보고서 요청 · 조회 경계의 실제 구현 — 보고서를 ai_reports에 버전별로 쌓는다 (ADR-0009)
// 지금은 FixedReportGenerator가 같은 자리에서 보고서를 만든다. P4-2에서 FastAPI 비동기 호출로 바꾼다
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportService implements ReportGateway {

    // 총 시도 횟수 (fds 정본 02 §2.5) — 버전 번호 충돌 재시도에도 같은 상한을 쓴다
    static final int MAX_ATTEMPTS = 3;

    private final AiReportRepository aiReportRepository;
    private final AiReportWriter aiReportWriter;
    private final FixedReportGenerator fixedReportGenerator;

    // 커밋 뒤에만 부른다 (트랜잭션 밖). 같은 증거 버전 요청이 두 번 와도 보고서는 하나다
    @Override
    public void request(Long caseId, int evidenceVersion) {
        createPending(caseId, evidenceVersion).ifPresent(this::run);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportSnapshot> findByCase(Long caseId) {
        return aiReportRepository.findByCaseIdOrderByReportVersionAsc(caseId).stream()
                .map(AiReportService::snapshot)
                .toList();
    }

    // 서버 재시작으로 PENDING · RUNNING에 멈춘 보고서를 같은 버전에서 처음부터 다시 만든다 (ADR-0009 한계)
    // P4-2에서는 RUNNING(FastAPI 접수됨)이면 다시 만들기 전에 FastAPI 결과부터 조회한다
    // 재개한 보고서의 Case ID를 돌려준다 — 호출자가 Case 상태를 다시 계산한다
    List<Long> resumeUnfinished() {
        List<Long> resumedCaseIds = new ArrayList<>();
        for (AiReport report : aiReportRepository.findByStatusIn(List.of(ReportStatus.PENDING, ReportStatus.RUNNING))) {
            log.info("미완료 보고서 재개 reportId={} caseId={}", report.getId(), report.getCaseId());
            try {
                run(report);
                resumedCaseIds.add(report.getCaseId());
            } catch (RuntimeException e) {
                log.warn("미완료 보고서 재개 실패 reportId={}", report.getId(), e);
            }
        }
        return resumedCaseIds;
    }

    // 동시 요청이 같은 report_version을 잡으면 늦은 쪽은 UNIQUE 위반 — 다시 읽어 합류하거나 다음 번호로 만든다 (ADR-0009 결정 3)
    private Optional<AiReport> createPending(Long caseId, int evidenceVersion) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return aiReportWriter.createPending(caseId, evidenceVersion, Instant.now());
            } catch (DataIntegrityViolationException e) {
                log.info("보고서 버전 충돌 — 다시 읽음 caseId={} attempt={}", caseId, attempt);
            }
        }
        log.warn("보고서 버전 충돌이 계속돼 요청을 건너뜀 caseId={} evidenceVersion={}", caseId, evidenceVersion);
        return Optional.empty();
    }

    // 조회 오류는 최대 3회 다시 시도하고, 모두 실패하면 FAILED로 끝낸다
    // 결제 근거가 빠진 경우는 다시 해도 같으므로 바로 FAILED — 직원은 수동 판정으로 간다 (fds 정본 02 §2.5)
    private void run(AiReport report) {
        String lastError = "UNKNOWN";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            try {
                Optional<Map<String, Object>> body = fixedReportGenerator.generate(
                        report.getCaseId(), report.getReportVersion(), report.getEvidenceVersion(), now);
                if (body.isEmpty()) {
                    aiReportWriter.fail(
                            report.getId(),
                            error("EVIDENCE_MISSING", "연결 결제의 금액 · 요청 시각 · FDS 점수 중 빠진 값이 있음", attempt, now),
                            now);
                    return;
                }
                aiReportWriter.succeed(report.getId(), body.get(), now);
                return;
            } catch (RuntimeException e) {
                lastError = e.getClass().getSimpleName();
                log.warn("보고서 생성 시도 실패 reportId={} attempt={}", report.getId(), attempt, e);
            }
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        aiReportWriter.fail(report.getId(), error("GENERATION_ERROR", lastError, MAX_ATTEMPTS, now), now);
    }

    // 실패 정보는 개발자 조사용 — 화면 응답(ReportSnapshot)에는 싣지 않는다 (ADR-0009 결정 2)
    private static Map<String, Object> error(String code, String reason, int attempts, Instant lastAttemptAt) {
        return Map.of(
                "code", code,
                "reason", reason,
                "attempts", attempts,
                "lastAttemptAt", lastAttemptAt.toString());
    }

    // 성공한 보고서만 본문을 내보낸다. FAILED의 error · 진행 중 보고서는 본문 null
    private static ReportSnapshot snapshot(AiReport report) {
        Map<String, Object> body = report.getStatus() == ReportStatus.SUCCEEDED ? report.getReportBody() : null;
        return new ReportSnapshot(
                report.getId(),
                report.getCaseId(),
                report.getReportVersion(),
                report.getEvidenceVersion(),
                report.getStatus().name(),
                body,
                report.getCreatedAt(),
                report.getCompletedAt());
    }
}
