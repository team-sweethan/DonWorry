package com.donworry.investigation.service;

import com.donworry.fds.api.InsiderAlertCloser;
import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockOutcome;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLockRelease;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.InsiderCaseAlertResponse;
import com.donworry.investigation.dto.InsiderCaseDecisionRequest;
import com.donworry.investigation.dto.InsiderCaseDetailResponse;
import com.donworry.investigation.dto.InsiderCaseResponse;
import com.donworry.investigation.dto.InsiderLockReleaseRequest;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.entity.InsiderCaseDecision;
import com.donworry.investigation.entity.InsiderCaseStatus;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import com.donworry.investigation.repository.InsiderCaseAlertRepository.CaseAlertCount;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 내부자 사건 (#670, ADR-0082 조각 8) — 높음 이상 경보를 직원 한 명당 열린 사건 하나로 묶고, ADMIN이 판정 3개 중 하나로 닫는다
// 사건 열기 · 판정 · 정지 해제는 FDS 사건 배정과 같은 잠금 행을 첫 문장에서 잡는다 — 사건이 열리는 순간 그 직원에게
// 새 FDS 사건이 배정되거나, 판정이 막 닫은 경보로 사건이 다시 열리지 않게 한 줄로 세운다
// 잠금 순서: 배정 잠금 행 → 내부자 사건 행 → P2 직원 탐지 행 → P1 계정 행 (내보내기 차단의 P2 → P1 순서와 같다)
// 판정 · 정지 해제는 READ_COMMITTED: P1 release가 권하는 격리 — 잠금을 기다린 뒤 최신 커밋 값을 읽는다
@Slf4j
@Service
@RequiredArgsConstructor
public class InsiderCaseService {
    // P1 정지 기록의 해제 판정 칸에 남기는 값 — 징계 사건의 나중 해제를 판정 3개와 구분한다
    static final String DISCIPLINE_LOCK_RELEASE = "DISCIPLINE_LOCK_RELEASE";

    private final InsiderCaseRepository cases;
    private final InsiderCaseAlertRepository links;
    private final SecurityCaseRepository securityCases;
    private final CaseAssignmentService caseAssignmentService;
    private final SecurityStaffDirectory staffDirectory;
    private final SecurityStaffGuard guard;
    private final InsiderAlertReader alertReader;
    private final InsiderAlertCloser alertCloser;
    private final StaffAccountLocker locker;

    // P2 경보 수신 — 열린 사건이 있으면 거기에, 없으면 새 사건. 같은 경보가 다시 와도(재전달 · 등급 상승) 한 줄
    @Transactional
    public void onAlertRaised(InsiderAlertRaised event) {
        if (!event.severity().isRaisedToOthers()) return;
        caseAssignmentService.lock();
        Instant now = Instant.now();
        Optional<InsiderCaseAlert> known = links.findById(event.alertId());
        if (known.isPresent()) {
            InsiderCaseAlert link = known.get();
            if (link.raiseTo(event.severity(), event.summary(), event.raisedAt())) {
                cases.findByIdForUpdate(link.getInsiderCaseId())
                        .filter(InsiderCase::isOpen)
                        .ifPresent(insiderCase -> insiderCase.touch(event.severity(), now));
            }
            return;
        }
        // 판정이 그사이 닫은 경보의 늦은 알림 — 풀어 준 직원에게 같은 경보로 사건을 다시 열지 않는다
        if (!alertReader.isOpen(event.alertId())) {
            log.info("닫힌 경보 알림이라 내부자 사건에 묶지 않음 alertId={} staffId={}", event.alertId(), event.staffId());
            return;
        }
        InsiderCase insiderCase = cases.findByStaffIdAndStatus(event.staffId(), InsiderCaseStatus.OPEN)
                .orElse(null);
        if (insiderCase == null) {
            insiderCase = cases.save(InsiderCase.open(event.staffId(), event.severity(), now));
            log.info("내부자 사건 열림 insiderCaseId={} staffId={}", insiderCase.getId(), event.staffId());
        } else {
            insiderCase.touch(event.severity(), now);
        }
        links.save(InsiderCaseAlert.link(
                insiderCase.getId(),
                event.alertId(),
                event.rule(),
                event.severity(),
                event.summary(),
                event.raisedAt(),
                now));
    }

    // 세 판정 모두 그 직원의 열린 경보를 닫는다 — 열린 채 정지를 풀면 다음 내보내기 시도에서 바로 다시 정지된다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decide(Long insiderCaseId, InsiderCaseDecisionRequest request) {
        StaffSummary admin = guard.requireAdmin();
        InsiderCaseDecision decision = parseDecision(request.decision());
        String memo = blankToNull(request.memo());
        caseAssignmentService.lock();
        InsiderCase insiderCase = cases.findByIdForUpdate(insiderCaseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.INSIDER_CASE_NOT_FOUND));
        if (!insiderCase.isOpen()) throw new InvestigationException(InvestigationErrorCode.INSIDER_CASE_CLOSED);
        Long staffId = insiderCase.getStaffId();
        if (!decision.releasesLock()) {
            // 정지된 직원의 고객 사건은 아무도 판정할 수 없다 — 먼저 넘기게 한다
            if (!securityCases.findOpenByAssignee(staffId).isEmpty()) {
                throw new InvestigationException(InvestigationErrorCode.INSIDER_CASE_HAS_OPEN_FDS_CASES);
            }
            // 정지는 따로 커밋된다 — 이 판정이 뒤에서 실패해도 정지는 남고, 다시 판정하면 "이미 정지"로 그대로 지나간다
            if (!locker.isLocked(staffId)) {
                Long sourceAlertId = links.findFirstByInsiderCaseIdOrderByRaisedAtDescAlertIdDesc(insiderCaseId)
                        .map(InsiderCaseAlert::getAlertId)
                        .orElse(null);
                locker.lockByAdmin(staffId, admin.userId(), StaffLockReason.INSIDER_CASE_DECISION, sourceAlertId);
            }
        }
        insiderCase.decide(decision, admin.userId(), admin.displayName(), memo, Instant.now());
        int closed = alertCloser.closeOpenAlerts(
                staffId,
                admin.userId(),
                admin.displayName(),
                "내부자 사건 #%d 판정: %s".formatted(insiderCaseId, decision.label()));
        if (decision.releasesLock()) {
            locker.release(StaffLockRelease.builder()
                    .staffId(staffId)
                    .adminId(admin.userId())
                    .caseId(insiderCaseId)
                    .decision(decision.name())
                    .outcome(StaffLockOutcome.RESTORE)
                    .build());
        }
        log.info(
                "내부자 사건 판정 insiderCaseId={} staffId={} decision={} closedAlerts={}",
                insiderCaseId,
                staffId,
                decision,
                closed);
    }

    // 징계로 닫힌 사건의 정지 해제 — 사유 필수, 한 번만. 그 직원에게 새로 열린 내부자 사건이 있으면 풀지 않는다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseLock(Long insiderCaseId, InsiderLockReleaseRequest request) {
        StaffSummary admin = guard.requireAdmin();
        StaffLockOutcome outcome = parseOutcome(request.outcome());
        String reason = request.reason().strip();
        caseAssignmentService.lock();
        InsiderCase insiderCase = cases.findByIdForUpdate(insiderCaseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.INSIDER_CASE_NOT_FOUND));
        if (!insiderCase.isDisciplined()) {
            throw new InvestigationException(InvestigationErrorCode.INSIDER_LOCK_RELEASE_NOT_ALLOWED);
        }
        if (insiderCase.isLockReleased()) {
            throw new InvestigationException(InvestigationErrorCode.INSIDER_LOCK_ALREADY_RELEASED);
        }
        Long staffId = insiderCase.getStaffId();
        if (cases.existsByStaffIdAndStatus(staffId, InsiderCaseStatus.OPEN)) {
            throw new InvestigationException(InvestigationErrorCode.STAFF_HAS_OPEN_INSIDER_CASE);
        }
        if (!locker.isLocked(staffId)) throw new InvestigationException(InvestigationErrorCode.STAFF_NOT_LOCKED);
        insiderCase.recordLockRelease(admin.userId(), admin.displayName(), reason, outcome, Instant.now());
        locker.release(StaffLockRelease.builder()
                .staffId(staffId)
                .adminId(admin.userId())
                .caseId(insiderCaseId)
                .decision(DISCIPLINE_LOCK_RELEASE)
                .outcome(outcome)
                .build());
        log.info("징계 사건 정지 해제 insiderCaseId={} staffId={} outcome={}", insiderCaseId, staffId, outcome);
    }

    @Transactional(readOnly = true)
    public List<InsiderCaseResponse> list(String statusName) {
        guard.requireAdmin();
        List<InsiderCase> rows = cases.findTop100ByStatusOrderByUpdatedAtDescIdDesc(parseStatus(statusName));
        if (rows.isEmpty()) return List.of();
        Map<Long, Long> counts = alertCounts(rows);
        Map<Long, String> names = staffNames();
        return rows.stream()
                .map(row -> toResponse(row, names, counts.getOrDefault(row.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public long openCount() {
        guard.requireAdmin();
        return cases.countByStatus(InsiderCaseStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public InsiderCaseDetailResponse detail(Long insiderCaseId) {
        guard.requireAdmin();
        InsiderCase insiderCase = cases.findById(insiderCaseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.INSIDER_CASE_NOT_FOUND));
        List<InsiderCaseAlertResponse> alerts =
                links.findByInsiderCaseIdOrderByRaisedAtAscAlertIdAsc(insiderCaseId).stream()
                        .map(link -> InsiderCaseAlertResponse.builder()
                                .alertId(link.getAlertId())
                                .ruleCode(link.getRuleCode().code())
                                .ruleLabel(link.getRuleCode().label())
                                .severity(link.getSeverity().name())
                                .summary(link.getSummary())
                                .raisedAt(link.getRaisedAt())
                                .linkedAt(link.getLinkedAt())
                                .build())
                        .toList();
        Long staffId = insiderCase.getStaffId();
        Map<Long, String> names = staffNames();
        List<InsiderCase> past = cases.findTop20ByStaffIdAndIdNotOrderByOpenedAtDescIdDesc(staffId, insiderCaseId);
        Map<Long, Long> pastCounts = alertCounts(past);
        return InsiderCaseDetailResponse.builder()
                .insiderCase(toResponse(insiderCase, names, alerts.size()))
                .alerts(alerts)
                .accountLocked(locker.isLocked(staffId))
                .openFdsCaseCount(securityCases.findOpenByAssignee(staffId).size())
                .pastCases(past.stream()
                        .map(row -> toResponse(row, names, pastCounts.getOrDefault(row.getId(), 0L)))
                        .toList())
                .build();
    }

    private Map<Long, Long> alertCounts(List<InsiderCase> rows) {
        if (rows.isEmpty()) return Map.of();
        return links.countByCaseIds(rows.stream().map(InsiderCase::getId).toList()).stream()
                .collect(Collectors.toMap(CaseAlertCount::getInsiderCaseId, CaseAlertCount::getAlertCount));
    }

    private static InsiderCaseResponse toResponse(InsiderCase row, Map<Long, String> names, long alertCount) {
        return InsiderCaseResponse.builder()
                .insiderCaseId(row.getId())
                .staffId(row.getStaffId())
                .staffName(names.get(row.getStaffId()))
                .status(row.getStatus().name())
                .severity(row.getSeverity().name())
                .alertCount(alertCount)
                .openedAt(row.getOpenedAt())
                .updatedAt(row.getUpdatedAt())
                .decision(row.getDecision() == null ? null : row.getDecision().name())
                .decisionLabel(
                        row.getDecision() == null ? null : row.getDecision().label())
                .decisionMemo(row.getDecisionMemo())
                .decidedByName(row.getDecidedByAdminName())
                .decidedAt(row.getDecidedAt())
                .lockReleasedAt(row.getLockReleasedAt())
                .lockReleasedByName(row.getLockReleasedByAdminName())
                .lockReleaseReason(row.getLockReleaseReason())
                .lockReleaseOutcome(
                        row.getLockReleaseOutcome() == null
                                ? null
                                : row.getLockReleaseOutcome().name())
                .build();
    }

    // 정지 · 비활성 직원도 이름을 보여야 한다 — 사건 대상은 대개 정지된 직원이다
    private Map<Long, String> staffNames() {
        return staffDirectory.securityStaff().stream()
                .collect(Collectors.toMap(SecurityStaffEntry::userId, SecurityStaffEntry::displayName, (a, b) -> a));
    }

    // controller가 entity enum을 모르게 문자열로 받는다 (ArchitectureTests)
    private static InsiderCaseDecision parseDecision(String name) {
        try {
            return InsiderCaseDecision.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_INSIDER_DECISION);
        }
    }

    private static InsiderCaseStatus parseStatus(String name) {
        try {
            return InsiderCaseStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_INSIDER_CASE_STATUS);
        }
    }

    private static StaffLockOutcome parseOutcome(String name) {
        try {
            return StaffLockOutcome.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_LOCK_OUTCOME);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
