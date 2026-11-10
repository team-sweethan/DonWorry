package com.donworry.investigation.report.service;

import com.donworry.fds.api.InsiderAlertEvidenceReader;
import com.donworry.fds.api.InsiderRule;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLogin;
import com.donworry.identity.api.StaffLoginEvent;
import com.donworry.identity.api.StaffLoginEventType;
import com.donworry.identity.api.StaffLoginReader;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.entity.InsiderCaseStatus;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.InsiderReportResponse;
import com.donworry.investigation.report.entity.InsiderReportTrigger;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.InsiderReportRepository;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InsiderCaseChanged;
import com.donworry.investigation.service.SecurityStaffGuard;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내부자 사건 해제 검토 보고서 (#675, ADR-0082 조각 9) — 사건 생성 · 차단 때 사실을 세어 판을 쌓는다
// 판정은 관리자가 한다. 보고서는 추천하지 않고, 사건 · 정지 상태를 바꾸는 호출을 하지 않는다 (AGENTS.md 6절)
// generate는 트랜잭션 밖에서 돈다(InsiderReportListener · InsiderReportBackfill)
// 사실 읽기가 실패하면 판을 남기지 않고 예외로 끝낸다 (#702) — 실패 판은 같은 계기를 막아 영영 다시 만들지 못했다.
// 알림은 event_publication에 실패로 남아 InsiderReportRedeliveryScheduler가 다시 보내고, 그래도 빠지면 다음 서버 시작 때 백필이 채운다
@Slf4j
@Service
@RequiredArgsConstructor
public class InsiderReportService {

    // 판 번호 충돌 재시도 상한 — 한 사건의 계기는 둘뿐이라 두 번이면 충분하고, 여유를 하나 둔다
    static final int MAX_SAVE_ATTEMPTS = 3;
    // INS-10 로그인 사실 (#770) — 경보 직전 성공 로그인을 찾는 범위 · 평소 브라우저 기간(탐지 ins10-lookback 기본값) · 로그인 전 실패를 세는 범위
    static final Duration LOGIN_BEFORE_ALERT = Duration.ofHours(1);
    static final Duration USUAL_LOGIN_LOOKBACK = Duration.ofDays(30);
    static final Duration FAILURE_LOOKBACK = Duration.ofHours(1);

    private final InsiderCaseRepository insiderCases;
    private final InsiderCaseAlertRepository links;
    private final SecurityCaseRepository securityCases;
    private final InsiderReportRepository reports;
    private final InsiderReportWriter writer;
    private final InsiderAlertEvidenceReader evidenceReader;
    private final StaffAccessReader accessReader;
    private final SecurityStaffDirectory staffDirectory;
    private final StaffAccountLocker locker;
    private final SecurityStaffGuard guard;
    private final StaffLoginReader loginReader;

    public void generate(InsiderCaseChanged event) {
        InsiderReportTrigger trigger = event.reason() == InsiderCaseChanged.Reason.EXPORT_BLOCKED
                ? InsiderReportTrigger.EXPORT_BLOCKED
                : InsiderReportTrigger.CASE_OPENED;
        generate(event.insiderCaseId(), trigger, event.alertId());
    }

    // 보고서 기능 배포 전부터 열려 있던 사건에 빠진 판을 채운다 (#675 Codex 리뷰) — 서버 시작 때 InsiderReportBackfill이 부른다
    // 알림은 사건이 새로 열리거나 차단 경보가 새로 붙을 때만 오므로, 그 전에 열린 사건(특히 이미 정지된 직원)은 이게 없으면 보고서가 없다
    // 알림 흐름과 같은 계기를 고른다: 첫 경보가 차단(INS-05)이면 차단 판 하나, 아니면 사건 생성 판 + (차단 경보가 있으면) 차단 판
    // 이미 있는 계기는 건너뛰므로 몇 번 돌아도 같다. 새로 만들려고 한 판 수를 돌려준다
    // 열린 사건 전부를 돈다(#675 Codex 2차 리뷰) — 화면 목록용 상한(100개) 조회를 쓰면 넘친 사건이 영영 빠진다. 사람당 열린 사건은 1개
    public int backfillOpenCases() {
        int requested = 0;
        for (Long staffId : insiderCases.findOpenStaffIds()) {
            InsiderCase insiderCase = insiderCases
                    .findByStaffIdAndStatus(staffId, InsiderCaseStatus.OPEN)
                    .orElse(null);
            if (insiderCase == null) continue; // 그사이 판정으로 닫힘
            Long insiderCaseId = insiderCase.getId();
            try {
                for (Map.Entry<InsiderReportTrigger, Long> expected :
                        expectedTriggers(insiderCaseId).entrySet()) {
                    if (InsiderReportWriter.isCovered(reports, insiderCaseId, expected.getKey())) continue;
                    generate(insiderCaseId, expected.getKey(), expected.getValue());
                    requested++;
                }
            } catch (RuntimeException e) {
                log.warn("해제 검토 보고서 채우기 실패 — 다음 시작 때 다시 시도 insiderCaseId={}", insiderCaseId, e);
            }
        }
        return requested;
    }

    // 계기별 계기 경보 — 사건 생성 판을 먼저(판 번호 순서)
    private Map<InsiderReportTrigger, Long> expectedTriggers(Long insiderCaseId) {
        List<InsiderCaseAlert> alerts = links.findByInsiderCaseIdOrderByRaisedAtAscAlertIdAsc(insiderCaseId);
        Map<InsiderReportTrigger, Long> expected = new LinkedHashMap<>();
        if (alerts.isEmpty()) return expected;
        InsiderCaseAlert first = alerts.getFirst();
        if (first.getRuleCode() != InsiderRule.INS_05)
            expected.put(InsiderReportTrigger.CASE_OPENED, first.getAlertId());
        alerts.stream()
                .filter(alert -> alert.getRuleCode() == InsiderRule.INS_05)
                .findFirst()
                .ifPresent(blocked -> expected.put(InsiderReportTrigger.EXPORT_BLOCKED, blocked.getAlertId()));
        return expected;
    }

    private void generate(Long insiderCaseId, InsiderReportTrigger trigger, Long alertId) {
        // 같은 알림이 다시 오거나 더 새 판(차단)이 이미 있으면 사실을 세지 않는다 — 저장할 때 한 번 더 보고, 동시면 UNIQUE가 막는다
        if (InsiderReportWriter.isCovered(reports, insiderCaseId, trigger)) return;
        InsiderCase insiderCase = insiderCases.findById(insiderCaseId).orElse(null);
        if (insiderCase == null) {
            log.warn("해제 검토 보고서 — 내부자 사건이 없음 insiderCaseId={}", insiderCaseId);
            return;
        }
        Instant now = Instant.now();
        Map<String, Object> body;
        try {
            body = facts(insiderCase, now);
        } catch (RuntimeException e) {
            log.warn("해제 검토 보고서 사실 읽기 실패 — 판을 남기지 않고 다시 보내기를 기다림 insiderCaseId={} trigger={}", insiderCaseId, trigger);
            throw e;
        }
        save(insiderCaseId, trigger, alertId, ReportStatus.SUCCEEDED, body, now);
    }

    // 관리자 화면 — 판 오름차순. 실패 판(#702 이전에 저장된 것)은 본문을 보내지 않는다
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
        return InsiderReportFacts.body(
                insiderCase,
                staffName(staffId),
                locker.isLocked(staffId),
                alerts,
                events,
                caseCustomers(events),
                baseline(insiderCase, events),
                loginAttack(staffId, alerts, now),
                now);
    }

    // SEC-INS-10 로그인 사실 (#770) — 경보가 열린 시각 직전의 성공 로그인을 탈취 의심 로그인으로 본다(INS-10 경보에는 근거 기록이 없다)
    // INS-10이 없거나 그 로그인을 찾지 못하면 null — 사실 묶음을 넣지 않는다
    private InsiderReportFacts.LoginAttack loginAttack(Long staffId, List<InsiderCaseAlert> alerts, Instant now) {
        InsiderCaseAlert suspected = alerts.stream()
                .filter(alert -> alert.getRuleCode() == InsiderRule.INS_10)
                .findFirst()
                .orElse(null);
        if (suspected == null) return null;
        Instant raisedAt = suspected.getRaisedAt();
        StaffLoginEvent login =
                loginReader.findLoginEvents(staffId, raisedAt.minus(LOGIN_BEFORE_ALERT), raisedAt).stream()
                        .filter(event -> event.eventType() == StaffLoginEventType.LOGIN_SUCCESS)
                        .reduce((earlier, later) -> later)
                        .orElse(null);
        if (login == null) return null;
        Instant loginAt = login.occurredAt();
        List<String> usualDevices =
                loginReader.findSuccessfulLogins(staffId, loginAt.minus(USUAL_LOGIN_LOOKBACK), loginAt).stream()
                        .map(StaffLogin::device)
                        .distinct()
                        .toList();
        long failuresBefore = loginReader.findLoginEvents(staffId, loginAt.minus(FAILURE_LOOKBACK), loginAt).stream()
                .filter(event -> event.eventType() == StaffLoginEventType.LOGIN_FAILURE
                        && event.occurredAt().isBefore(loginAt))
                .count();
        List<StaffAccessRecorded> after = accessReader.findRecent(staffId, loginAt, now);
        Long exportSeconds = after.stream()
                .filter(event -> event.accessType() == StaffAccessType.TRANSACTION_EXPORT)
                .map(StaffAccessRecorded::occurredAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(at -> Duration.between(loginAt, at).toSeconds())
                .orElse(null);
        return InsiderReportFacts.LoginAttack.builder()
                .loginAt(loginAt)
                .device(login.device())
                .usualDevices(usualDevices)
                .failuresBeforeLogin(failuresBefore)
                .accessAfterLogin(after.size())
                .firstExportAttemptSeconds(exportSeconds)
                .build();
    }

    // 평소(30일) 기록 (#749) — 경보 근거 첫 기록이 있는 한국 날짜의 전날까지, 완전한 한국 날짜 30일 [D-30 0시, D 0시).
    // 근거 기록이 없으면 사건이 열린 날 기준. 하루 단위 집계와 경계를 맞춰 날짜가 31개가 되거나 반쪽 날이 최대 후보가 되지 않게 한다 (#755 Codex 리뷰)
    // 읽기 창구는 끝 시각을 포함하므로 끝 시각의 기록과 근거 기록은 빼서, 이번 행동이 평소 값에 섞이지 않게 한다
    private InsiderReportFacts.Baseline baseline(InsiderCase insiderCase, List<StaffAccessRecorded> evidence) {
        Instant first = evidence.stream()
                .map(StaffAccessRecorded::occurredAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(insiderCase.getOpenedAt());
        LocalDate evidenceDay = LocalDate.ofInstant(first, InsiderReportFacts.SEOUL);
        Instant to = evidenceDay.atStartOfDay(InsiderReportFacts.SEOUL).toInstant();
        Instant from = evidenceDay
                .minusDays(InsiderReportFacts.BASELINE_DAYS)
                .atStartOfDay(InsiderReportFacts.SEOUL)
                .toInstant();
        Set<Long> evidenceIds =
                evidence.stream().map(StaffAccessRecorded::eventId).collect(Collectors.toSet());
        List<StaffAccessRecorded> usual = accessReader.findRecent(insiderCase.getStaffId(), from, to).stream()
                .filter(event ->
                        event.occurredAt() != null && event.occurredAt().isBefore(to))
                .filter(event -> !evidenceIds.contains(event.eventId()))
                .toList();
        return new InsiderReportFacts.Baseline(from, to, usual);
    }

    // 고객 번호가 비어 있는 기록(원문 열람 · 사건 상세 등)의 고객을 사건 번호로 찾는다 (#675 Codex 리뷰) — 집계에만 쓰고 본문에는 넣지 않는다
    private Map<Long, String> caseCustomers(List<StaffAccessRecorded> events) {
        Map<Long, String> customers = new LinkedHashMap<>();
        events.stream()
                .filter(event -> event.customerRef() == null && event.caseId() != null)
                .map(StaffAccessRecorded::caseId)
                .distinct()
                .forEach(caseId -> securityCases
                        .findById(caseId)
                        .map(SecurityCase::getCustomerRef)
                        .filter(Objects::nonNull)
                        .ifPresent(ref -> customers.put(caseId, ref)));
        return customers;
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
