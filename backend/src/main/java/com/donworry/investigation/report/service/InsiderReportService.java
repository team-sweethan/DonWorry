package com.donworry.investigation.report.service;

import com.donworry.fds.api.InsiderAlertEvidenceReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.report.dto.InsiderReportResponse;
import com.donworry.investigation.report.entity.InsiderReportTrigger;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.InsiderReportRepository;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.service.InsiderCaseChanged;
import com.donworry.investigation.service.SecurityStaffGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내부자 사건 해제 검토 보고서 (#675, ADR-0082 조각 9) — 사건 생성 · 차단 때 사실을 세어 판을 쌓는다
// 판정은 관리자가 한다. 보고서는 추천하지 않고, 사건 · 정지 상태를 바꾸는 호출을 하지 않는다 (AGENTS.md 6절)
// generate는 트랜잭션 밖에서 돈다(InsiderReportListener) — 사실 읽기가 실패해도 실패 판을 남기고 끝난다
@Slf4j
@Service
@RequiredArgsConstructor
public class InsiderReportService {

    // 판 번호 충돌 재시도 상한 — 한 사건의 계기는 둘뿐이라 두 번이면 충분하고, 여유를 하나 둔다
    static final int MAX_SAVE_ATTEMPTS = 3;

    static final String FACTS_UNAVAILABLE = "FACTS_UNAVAILABLE";

    private final InsiderCaseRepository insiderCases;
    private final InsiderCaseAlertRepository links;
    private final InsiderReportRepository reports;
    private final InsiderReportWriter writer;
    private final InsiderAlertEvidenceReader evidenceReader;
    private final StaffAccessReader accessReader;
    private final SecurityStaffDirectory staffDirectory;
    private final StaffAccountLocker locker;
    private final SecurityStaffGuard guard;

    public void generate(InsiderCaseChanged event) {
        InsiderReportTrigger trigger = event.reason() == InsiderCaseChanged.Reason.EXPORT_BLOCKED
                ? InsiderReportTrigger.EXPORT_BLOCKED
                : InsiderReportTrigger.CASE_OPENED;
        Long insiderCaseId = event.insiderCaseId();
        // 같은 알림이 다시 오면 사실을 다시 세지 않는다 — 저장할 때 한 번 더 보고, 동시면 UNIQUE가 막는다
        if (reports.existsByInsiderCaseIdAndTriggerType(insiderCaseId, trigger)) return;
        InsiderCase insiderCase = insiderCases.findById(insiderCaseId).orElse(null);
        if (insiderCase == null) {
            log.warn("해제 검토 보고서 — 내부자 사건이 없음 insiderCaseId={}", insiderCaseId);
            return;
        }
        Instant now = Instant.now();
        Map<String, Object> body;
        ReportStatus status;
        try {
            body = facts(insiderCase, now);
            status = ReportStatus.SUCCEEDED;
        } catch (RuntimeException e) {
            log.warn("해제 검토 보고서 사실 읽기 실패 insiderCaseId={} trigger={}", insiderCaseId, trigger, e);
            body = Map.of("error", Map.of("code", FACTS_UNAVAILABLE));
            status = ReportStatus.FAILED;
        }
        save(insiderCaseId, trigger, event.alertId(), status, body, now);
    }

    // 관리자 화면 — 판 오름차순. 실패 판은 본문을 보내지 않는다
    @Transactional(readOnly = true)
    public List<InsiderReportResponse> list(Long insiderCaseId) {
        guard.requireAdmin();
        return reports.findByInsiderCaseIdOrderByReportVersionAsc(insiderCaseId).stream()
                .map(InsiderReportResponse::from)
                .toList();
    }

    private Map<String, Object> facts(InsiderCase insiderCase, Instant now) {
        List<InsiderCaseAlert> alerts = links.findByInsiderCaseIdOrderByRaisedAtAscAlertIdAsc(insiderCase.getId());
        List<Long> eventIds = evidenceReader.accessEventIds(
                alerts.stream().map(InsiderCaseAlert::getAlertId).toList());
        List<StaffAccessRecorded> events = eventIds.isEmpty() ? List.of() : accessReader.findByIds(eventIds);
        Long staffId = insiderCase.getStaffId();
        return InsiderReportFacts.body(insiderCase, staffName(staffId), locker.isLocked(staffId), alerts, events, now);
    }

    private void save(
            Long insiderCaseId,
            InsiderReportTrigger trigger,
            Long alertId,
            ReportStatus status,
            Map<String, Object> body,
            Instant now) {
        for (int attempt = 1; attempt <= MAX_SAVE_ATTEMPTS; attempt++) {
            try {
                if (writer.save(insiderCaseId, trigger, alertId, status, body, now)) {
                    log.info("해제 검토 보고서 저장 insiderCaseId={} trigger={} status={}", insiderCaseId, trigger, status);
                }
                return;
            } catch (DataIntegrityViolationException e) {
                log.info("해제 검토 보고서 판 번호 충돌 — 다시 읽음 insiderCaseId={} attempt={}", insiderCaseId, attempt);
            }
        }
        log.warn("해제 검토 보고서 판 번호 충돌이 계속돼 건너뜀 insiderCaseId={} trigger={}", insiderCaseId, trigger);
    }

    private String staffName(Long staffId) {
        return staffDirectory.securityStaff().stream()
                .filter(staff -> staff.userId().equals(staffId))
                .map(SecurityStaffEntry::displayName)
                .findFirst()
                .orElse(null);
    }
}
