package com.donworry.fds.service;

import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertEvidence;
import com.donworry.fds.entity.InsiderAlertStatus;
import com.donworry.fds.repository.InsiderAlertEvidenceRepository;
import com.donworry.fds.repository.InsiderAlertRepository;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessResult;
import com.donworry.identity.api.StaffAccessType;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 접근 기록 1건이 올 때마다 그 직원의 최근 기록만 다시 읽어 내부자 경보를 만든다 (#516, ADR-0082)
// 기준 시각은 받은 시각이 아니라 기록 시각 — 재전달돼도 같은 창으로 센다. 점수 없이 조건만 본다
// 경보는 운영 데이터(계정 · 사건 · 결제)를 바꾸지 않는다. 조치는 InsiderAlertRaised를 받는 쪽(조각 8)과 7a가 맡는다
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InsiderDetectionProperties.class)
public class InsiderDetectionService {
    private static final String SECURITY_STAFF = "SECURITY_STAFF";
    // 담당 밖 시도로 보는 거부 이유. CASE_CLOSED는 지난 사건 확인처럼 정상 업무에서도 나와 세지 않는다 (#516 11/07 결정)
    private static final Set<StaffAccessDenyReason> OUT_OF_SCOPE =
            Set.of(StaffAccessDenyReason.NOT_ASSIGNEE, StaffAccessDenyReason.PAYMENT_NOT_IN_CASE);

    private final InsiderAlertRepository alerts;
    private final InsiderAlertEvidenceRepository evidence;
    private final StaffAccessReader accessReader;
    private final ApplicationEventPublisher publisher;
    private final InsiderDetectionProperties properties;

    @Transactional
    public void detect(StaffAccessRecorded event) {
        // ADMIN 접근은 이 규칙의 대상이 아니다. 담당 밖 거부 · 원문 열람만 센다(화면 열람은 세지 않음)
        if (!SECURITY_STAFF.equals(event.actorRole())) return;
        boolean outOfScope = isOutOfScopeDenial(event);
        boolean rawView = isRawView(event);
        if (!outOfScope && !rawView) return;

        Long staffId = event.staffId();
        alerts.lockDetectionState(staffId);

        Instant at = event.occurredAt();
        if (outOfScope) checkIns01(staffId, at);
        if (rawView) checkIns03(staffId, at);
        checkIns06(staffId, at);
    }

    // INS-01 담당 밖 접근 반복 — 짧은 시간에 서로 다른 사건 N개에서 막히면 주의. 같은 사건 재시도는 한 번으로 센다
    private void checkIns01(Long staffId, Instant at) {
        Duration window = properties.ins01Window();
        List<StaffAccessRecorded> denials =
                recent(staffId, InsiderRule.INS_01, at.minus(window), at.plus(window)).stream()
                        .filter(InsiderDetectionService::isOutOfScopeDenial)
                        .toList();
        List<StaffAccessRecorded> hit = windowReaching(
                denials, at, window, InsiderDetectionService::distinctCases, properties.ins01CautionCases());
        if (hit.isEmpty()) return;
        raise(
                staffId,
                InsiderRule.INS_01,
                InsiderAlertSeverity.CAUTION,
                "%d분 안에 담당 밖 사건 %d개 접근 거부".formatted(window.toMinutes(), distinctCases(hit)),
                hit);
    }

    // INS-03 원문 열람 집중 — 한 직원이 짧은 시간에 주문자 전화 원문을 N건 열면 주의
    private void checkIns03(Long staffId, Instant at) {
        Duration window = properties.ins03Window();
        List<StaffAccessRecorded> views =
                recent(staffId, InsiderRule.INS_03, at.minus(window), at.plus(window)).stream()
                        .filter(InsiderDetectionService::isRawView)
                        .toList();
        List<StaffAccessRecorded> hit =
                windowReaching(views, at, window, List::size, properties.ins03CautionRawViews());
        if (hit.isEmpty()) return;
        raise(
                staffId,
                InsiderRule.INS_03,
                InsiderAlertSeverity.CAUTION,
                "%d분 안에 주문자 전화 원문 %d건 열람".formatted(window.toMinutes(), hit.size()),
                hit);
    }

    // 이 기록이 들어가는 창(끝이 이 기록 ~ 창 길이 뒤)을 앞에서부터 보고, 기준에 닿은 첫 창의 기록을 돌려준다
    // 먼저 일어난 기록이 늦게 커밋돼 마지막에 처리돼도 뒤 기록과 함께 센다 (#627 리뷰). 기록은 시각 순으로 온다
    private static List<StaffAccessRecorded> windowReaching(
            List<StaffAccessRecorded> records,
            Instant at,
            Duration window,
            ToLongFunction<List<StaffAccessRecorded>> measure,
            long threshold) {
        List<Instant> ends = Stream.concat(
                        Stream.of(at),
                        records.stream().map(StaffAccessRecorded::occurredAt).filter(t -> t.isAfter(at)))
                .distinct()
                .sorted()
                .toList();
        for (Instant end : ends) {
            Instant start = end.minus(window);
            List<StaffAccessRecorded> inWindow = records.stream()
                    .filter(r ->
                            !r.occurredAt().isBefore(start) && !r.occurredAt().isAfter(end))
                    .toList();
            if (measure.applyAsLong(inWindow) >= threshold) return inWindow;
        }
        return List.of();
    }

    private static long distinctCases(List<StaffAccessRecorded> records) {
        return records.stream()
                .map(StaffAccessRecorded::caseId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    // INS-06 경보 겹침 — 같은 직원에게 서로 다른 규칙 경보가 2개 이상 열려 있으면 높음
    // INS-06이 닫힌 적 있으면 그 뒤 새 기록이 붙은 경보만 센다 — 관리자가 정상으로 본 같은 겹침으로 다시 열지 않는다 (#627 리뷰)
    private void checkIns06(Long staffId, Instant at) {
        Instant lastClosed = alerts.findLastClosedAt(staffId, InsiderRule.INS_06);
        List<InsiderAlert> others = alerts.findByStaffIdAndStatus(staffId, InsiderAlertStatus.OPEN).stream()
                .filter(a -> a.getRuleCode() != InsiderRule.INS_06)
                .filter(a -> lastClosed == null || a.getLastEventAt().isAfter(lastClosed))
                .toList();
        List<InsiderRule> rules = others.stream()
                .map(InsiderAlert::getRuleCode)
                .distinct()
                .sorted()
                .toList();
        if (rules.size() < 2) return;
        List<Long> eventIds = evidence.findAccessEventIdsOf(
                others.stream().map(InsiderAlert::getId).toList());
        Instant firstAt = others.stream()
                .map(InsiderAlert::getFirstEventAt)
                .min(Comparator.naturalOrder())
                .orElse(at);
        String summary = "열린 경보 %d개 겹침: %s"
                .formatted(rules.size(), rules.stream().map(InsiderRule::code).collect(Collectors.joining(" · ")));
        raise(staffId, InsiderRule.INS_06, InsiderAlertSeverity.HIGH, summary, eventIds, firstAt, at);
    }

    private void raise(
            Long staffId,
            InsiderRule rule,
            InsiderAlertSeverity severity,
            String summary,
            List<StaffAccessRecorded> events) {
        raise(
                staffId,
                rule,
                severity,
                summary,
                events.stream().map(StaffAccessRecorded::eventId).toList(),
                events.getFirst().occurredAt(),
                events.getLast().occurredAt());
    }

    // 사람 · 규칙당 열린 경보는 1개 — 있으면 같은 행을 갱신(등급은 올리기만), 없으면 새로 연다
    private void raise(
            Long staffId,
            InsiderRule rule,
            InsiderAlertSeverity severity,
            String summary,
            List<Long> eventIds,
            Instant firstAt,
            Instant lastAt) {
        Instant now = Instant.now();
        var existing = alerts.findByStaffIdAndRuleCodeAndStatus(staffId, rule, InsiderAlertStatus.OPEN);
        InsiderAlert alert;
        boolean publish;
        if (existing.isPresent()) {
            alert = existing.get();
            publish = alert.refresh(severity, summary, lastAt, now)
                    && alert.getSeverity().isRaisedToOthers();
        } else {
            alert = alerts.save(InsiderAlert.open(staffId, rule, severity, summary, firstAt, lastAt, now));
            publish = severity.isRaisedToOthers();
            log.info("내부자 경보 열림 alertId={} staffId={} rule={} severity={}", alert.getId(), staffId, rule, severity);
        }
        addEvidence(alert.getId(), eventIds, now);
        if (publish) {
            publisher.publishEvent(InsiderAlertRaised.builder()
                    .alertId(alert.getId())
                    .staffId(staffId)
                    .rule(rule)
                    .severity(alert.getSeverity())
                    .summary(alert.getSummary())
                    .raisedAt(now)
                    .build());
        }
    }

    // 이미 있는 근거는 다시 넣지 않는다 — 같은 기록이 다시 와도 한 줄 (DB UNIQUE가 마지막 방어)
    private void addEvidence(Long alertId, List<Long> eventIds, Instant now) {
        Set<Long> known = new HashSet<>(evidence.findAccessEventIds(alertId));
        for (Long eventId : eventIds) {
            if (known.add(eventId)) evidence.save(InsiderAlertEvidence.of(alertId, eventId, now));
        }
    }

    // 이 규칙이 마지막으로 닫힌 뒤의 기록만 센다 — 관리자가 정상으로 닫은 근거로 다시 경보가 뜨지 않게
    private List<StaffAccessRecorded> recent(Long staffId, InsiderRule rule, Instant from, Instant to) {
        Instant lastClosed = alerts.findLastClosedAt(staffId, rule);
        Instant since = lastClosed != null && !lastClosed.isBefore(from) ? lastClosed.plusNanos(1_000) : from;
        return accessReader.findRecent(staffId, since, to);
    }

    private static boolean isOutOfScopeDenial(StaffAccessRecorded e) {
        // Set.of는 null을 물으면 예외라 먼저 거른다
        return e.result() == StaffAccessResult.DENIED
                && e.denyReason() != null
                && OUT_OF_SCOPE.contains(e.denyReason());
    }

    private static boolean isRawView(StaffAccessRecorded e) {
        return e.result() == StaffAccessResult.ALLOWED && e.accessType() == StaffAccessType.CONTACT_RAW_VIEW;
    }
}
