package com.donworry.investigation.report.service;

import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.AiReportRepository;
import com.donworry.investigation.report.repository.CaseReportStatus;
import com.donworry.investigation.service.ReportGateway;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// P3 → P4 보고서 요청 · 조회 경계의 실제 구현 — 보고서를 ai_reports에 버전별로 쌓는다 (ADR-0009)
// AI를 쓸 수 없으면(설정 ai.server.enabled 꺼짐 · 긴급정지, ADR-0083) FixedReportRunner가 같은 자리에서 고정 문장 보고서를 만든다
// 쓸 수 있으면 ai-server에 비동기로 맡기고, 결과는 AiReportResultService가 받는다
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportService implements ReportGateway {

    // 총 시도 횟수 (fds 정본 02 §2.5) — 버전 번호 충돌 재시도에도 같은 상한을 쓴다
    static final int MAX_ATTEMPTS = 3;

    private final AiReportRepository aiReportRepository;
    private final AiReportWriter aiReportWriter;
    private final FixedReportRunner fixedReportRunner;
    private final AiSwitchService aiSwitchService;
    private final AiInvestigationRunner aiInvestigationRunner;
    private final AiReportResultService aiReportResultService;

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

    // 목록 화면용 — 본문 없이 Case별 최신 보고서 상태만 한 번에 읽는다
    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> latestStatuses(Collection<Long> caseIds) {
        Map<Long, String> statuses = new HashMap<>();
        if (caseIds.isEmpty()) {
            return statuses;
        }
        for (CaseReportStatus row : aiReportRepository.findLatestStatusByCaseIdIn(caseIds)) {
            statuses.put(row.caseId(), row.status().name());
        }
        return statuses;
    }

    // 서버 재시작으로 PENDING · RUNNING에 멈춘 보고서를 같은 버전에서 다시 만든다 (ADR-0009 한계)
    // AI를 쓸 수 있으면 RUNNING(ai-server 접수됨)은 다시 만들기 전에 ai-server 결과부터 조회한다. 긴급정지 중이면 고정 보고서로 끝낸다
    // 재개한 보고서의 Case ID를 돌려준다 — 호출자가 Case 상태를 다시 계산한다
    List<Long> resumeUnfinished() {
        List<Long> resumedCaseIds = new ArrayList<>();
        for (AiReport report : aiReportRepository.findByStatusIn(List.of(ReportStatus.PENDING, ReportStatus.RUNNING))) {
            log.info("미완료 보고서 재개 reportId={} caseId={}", report.getId(), report.getCaseId());
            try {
                if (aiSwitchService.isAiAllowed()) {
                    aiReportResultService.recover(report);
                } else {
                    fixedReportRunner.run(report);
                }
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

    private void run(AiReport report) {
        if (aiSwitchService.isAiAllowed()) {
            aiInvestigationRunner.start(report);
        } else {
            fixedReportRunner.run(report);
        }
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
