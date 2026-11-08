package com.donworry.investigation.service;

import com.donworry.fds.api.InsiderAlertEntry;
import com.donworry.fds.api.InsiderAlertMonitorReader;
import com.donworry.fds.api.StaffCaseScope;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockRecord;
import com.donworry.identity.api.StaffLoginEvent;
import com.donworry.identity.api.StaffLoginEventType;
import com.donworry.identity.api.StaffLoginReader;
import com.donworry.investigation.dto.InsiderMonitorEventResponse;
import com.donworry.investigation.dto.InsiderMonitorStaffResponse;
import com.donworry.investigation.dto.InsiderMonitorStage;
import com.donworry.investigation.dto.InsiderMonitorTimelineResponse;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseStatus;
import com.donworry.investigation.repository.InsiderCaseRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내부자 관제 한 화면 (#679, ADR-0082 조각 10) — 이미 있는 기록(로그인 · 접근 · 경보 · 내부자 사건 · 정지)을 직원 기준으로 모은다
// 새로 쌓지 않는다. 원문 · 고객 번호는 내보내지 않고, 고객은 "담당 사건 고객 / 무관 고객"으로만 구분한다
@Service
@RequiredArgsConstructor
public class InsiderMonitorService {

    // 마지막 로그인 성공을 찾는 범위와, 그 직전 실패를 함께 보여 줄 시간
    static final Duration LOGIN_LOOKBACK = Duration.ofDays(1);
    static final Duration BEFORE_LOGIN = Duration.ofMinutes(30);
    // 탐지(INS-08 · 09)의 담당 범위 기본값(fds scopeClosedLookback 30d)과 맞춘다. 범위는 탐지 시각이 아닌 지금 기준이다
    static final Duration SCOPE_CLOSED_LOOKBACK = Duration.ofDays(30);
    static final int MAX_EVENTS = 200;

    // 같은 초 안이면 원인 → 결과 순서 — 거부 기록이 정지보다 몇 ms 늦게 저장될 수 있다
    private static final int ORDER_LOGIN = 0;
    private static final int ORDER_ACCESS = 1;
    private static final int ORDER_ALERT = 2;
    private static final int ORDER_LOCK = 3;

    private final SecurityStaffGuard guard;
    private final SecurityStaffDirectory staffDirectory;
    private final InsiderAlertMonitorReader alertReader;
    private final InsiderCaseRepository insiderCases;
    private final StaffAccountLocker locker;
    private final StaffLoginReader loginReader;
    private final StaffAccessReader accessReader;
    private final StaffCaseScopeReader scopeReader;
    private final MerchantDirectoryReader merchantDirectory;

    // 사용 중 직원과 정지 직원만 — 징계 뒤 비활성으로 바뀐 직원은 숨긴다 (#679 결정 1)
    @Transactional(readOnly = true)
    public List<InsiderMonitorStaffResponse> staff() {
        guard.requireAdmin();
        Map<Long, Long> openAlerts = openAlertCounts();
        Map<Long, Long> openCases = openCaseIds();
        List<InsiderMonitorStaffResponse> result = new ArrayList<>();
        for (SecurityStaffEntry s : staffDirectory.securityStaff()) {
            boolean locked = locker.isLocked(s.userId());
            if (s.active() || locked) result.add(toStaff(s, locked, openAlerts, openCases));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public InsiderMonitorTimelineResponse timeline(Long staffId) {
        guard.requireAdmin();
        return timeline(staffId, Instant.now());
    }

    InsiderMonitorTimelineResponse timeline(Long staffId, Instant now) {
        SecurityStaffEntry staff = staffDirectory.securityStaff().stream()
                .filter(s -> s.userId().equals(staffId))
                .findFirst()
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.MONITOR_STAFF_NOT_FOUND));
        List<StaffLoginEvent> logins =
                loginReader.findLoginEvents(staffId, now.minus(LOGIN_LOOKBACK).minus(BEFORE_LOGIN), now);
        Instant from = timelineStart(logins, now);

        List<Row> rows = new ArrayList<>();
        logins.stream().filter(l -> !l.occurredAt().isBefore(from)).forEach(l -> rows.add(login(l)));
        addAccess(rows, staffId, from, now);
        for (InsiderAlertEntry a : alertReader.findAlerts(staffId, from, now)) {
            if (within(a.createdAt(), from, now)) rows.add(alert(a, "ALERT_RAISED", a.createdAt()));
            if (within(a.closedAt(), from, now)) rows.add(alert(a, "ALERT_CLOSED", a.closedAt()));
        }
        List<StaffLockRecord> locks = locker.findLocks(staffId);
        for (StaffLockRecord l : locks) {
            if (within(l.lockedAt(), from, now)) rows.add(lock(l, "LOCKED", l.lockedAt()));
            if (within(l.releasedAt(), from, now)) rows.add(lock(l, "RELEASED", l.releasedAt()));
        }
        rows.sort(Comparator.comparing((Row r) -> r.at().truncatedTo(ChronoUnit.SECONDS))
                .thenComparingInt(Row::order)
                .thenComparing(Row::at));
        boolean truncated = rows.size() > MAX_EVENTS;
        List<InsiderMonitorEventResponse> events =
                rows.subList(Math.max(0, rows.size() - MAX_EVENTS), rows.size()).stream()
                        .map(Row::event)
                        .toList();

        boolean locked = locks.stream().anyMatch(StaffLockRecord::open);
        Map<Long, Long> openAlerts = openAlertCounts();
        Map<Long, Long> openCases = insiderCases
                .findByStaffIdAndStatus(staffId, InsiderCaseStatus.OPEN)
                .map(c -> Map.of(staffId, c.getId()))
                .orElse(Map.of());
        return InsiderMonitorTimelineResponse.builder()
                .staff(toStaff(staff, locked, openAlerts, openCases))
                .from(from)
                .to(now)
                .truncated(truncated)
                .events(events)
                .build();
    }

    // 정지가 가장 앞선다(사건 없이 INS-05로 바로 정지될 수 있다). 다음은 사건, 그다음 열린 경보
    static InsiderMonitorStage stage(boolean locked, Long openInsiderCaseId, long openAlertCount) {
        if (locked) return InsiderMonitorStage.BLOCKED;
        if (openInsiderCaseId != null) return InsiderMonitorStage.INSIDER_CASE;
        if (openAlertCount > 0) return InsiderMonitorStage.CAUTION;
        return InsiderMonitorStage.NORMAL;
    }

    // 하루 안의 마지막 로그인 성공 30분 전부터 — 그 직전 비밀번호 실패를 함께 보여 준다. 로그인이 없으면 하루 전부터
    private static Instant timelineStart(List<StaffLoginEvent> logins, Instant now) {
        Instant lookback = now.minus(LOGIN_LOOKBACK);
        return logins.stream()
                .filter(l -> l.eventType() == StaffLoginEventType.LOGIN_SUCCESS)
                .map(StaffLoginEvent::occurredAt)
                .filter(at -> !at.isBefore(lookback))
                .max(Comparator.naturalOrder())
                .map(at -> at.minus(BEFORE_LOGIN))
                .orElse(lookback);
    }

    private InsiderMonitorStaffResponse toStaff(
            SecurityStaffEntry s, boolean locked, Map<Long, Long> openAlerts, Map<Long, Long> openCases) {
        Long caseId = openCases.get(s.userId());
        long alertCount = openAlerts.getOrDefault(s.userId(), 0L);
        return InsiderMonitorStaffResponse.builder()
                .staffId(s.userId())
                .staffName(s.displayName())
                .stage(stage(locked, caseId, alertCount))
                .openAlertCount(alertCount)
                .openInsiderCaseId(caseId)
                .locked(locked)
                .build();
    }

    private Map<Long, Long> openAlertCounts() {
        return alertReader.findOpenAlerts().stream()
                .collect(Collectors.groupingBy(InsiderAlertEntry::staffId, Collectors.counting()));
    }

    // 사람당 열린 내부자 사건은 1개 (#670 uk_insider_cases_open)
    private Map<Long, Long> openCaseIds() {
        return insiderCases.findByStatusOrderByIdAsc(InsiderCaseStatus.OPEN).stream()
                .collect(Collectors.toMap(InsiderCase::getStaffId, InsiderCase::getId, (a, b) -> a));
    }

    private void addAccess(List<Row> rows, Long staffId, Instant from, Instant now) {
        List<StaffAccessRecorded> access = accessReader.findRecent(staffId, from, now);
        if (access.isEmpty()) return;
        Map<Long, String> merchantNames = merchantDirectory.findAll().stream()
                .collect(Collectors.toMap(
                        MerchantDirectoryEntry::merchantId, MerchantDirectoryEntry::displayName, (a, b) -> a));
        // 고객 번호 검색이 있을 때만 담당 범위를 읽는다
        StaffCaseScope scope = access.stream().anyMatch(e -> e.customerRef() != null)
                ? scopeReader.findScope(staffId, now.minus(SCOPE_CLOSED_LOOKBACK))
                : null;
        for (StaffAccessRecorded e : access) {
            rows.add(new Row(
                    e.occurredAt(),
                    ORDER_ACCESS,
                    InsiderMonitorEventResponse.builder()
                            .occurredAt(e.occurredAt())
                            .kind("ACCESS")
                            .type(e.accessType().name())
                            .result(e.result().name())
                            .denyReason(
                                    e.denyReason() == null
                                            ? null
                                            : e.denyReason().name())
                            .caseId(e.caseId())
                            .merchantId(e.merchantId())
                            .merchantName(e.merchantId() == null ? null : merchantNames.get(e.merchantId()))
                            .paymentId(e.paymentId())
                            .fieldType(
                                    e.fieldType() == null ? null : e.fieldType().name())
                            .itemCount(e.itemCount())
                            .customerScope(customerScope(scope, e))
                            .build()));
        }
    }

    // 탐지(INS-09)와 같은 대조 — 가맹점을 고른 검색이면 그 가맹점의 고객인지까지 본다
    private static String customerScope(StaffCaseScope scope, StaffAccessRecorded e) {
        if (e.customerRef() == null) return null;
        return scope.hasCustomer(e.merchantId(), e.customerRef()) ? "RELATED" : "UNRELATED";
    }

    private static Row login(StaffLoginEvent l) {
        return new Row(
                l.occurredAt(),
                ORDER_LOGIN,
                InsiderMonitorEventResponse.builder()
                        .occurredAt(l.occurredAt())
                        .kind("LOGIN")
                        .type(l.eventType().name())
                        .clientIp(l.clientIp())
                        .device(l.device())
                        .build());
    }

    private static Row alert(InsiderAlertEntry a, String type, Instant at) {
        return new Row(
                at,
                ORDER_ALERT,
                InsiderMonitorEventResponse.builder()
                        .occurredAt(at)
                        .kind("ALERT")
                        .type(type)
                        .alertId(a.alertId())
                        .ruleCode(a.rule().code())
                        .ruleLabel(a.rule().label())
                        .severity(a.severity().name())
                        .summary(a.summary())
                        .build());
    }

    private static Row lock(StaffLockRecord l, String type, Instant at) {
        boolean released = "RELEASED".equals(type);
        return new Row(
                at,
                ORDER_LOCK,
                InsiderMonitorEventResponse.builder()
                        .occurredAt(at)
                        .kind("LOCK")
                        .type(type)
                        .lockReason(l.reason() == null ? null : l.reason().name())
                        .alertId(l.sourceAlertId())
                        .insiderCaseId(released ? l.releaseCaseId() : null)
                        .decision(released ? l.releaseDecision() : null)
                        .build());
    }

    private static boolean within(Instant at, Instant from, Instant to) {
        return at != null && !at.isBefore(from) && !at.isAfter(to);
    }

    private record Row(Instant at, int order, InsiderMonitorEventResponse event) {}
}
