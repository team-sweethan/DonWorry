package com.donworry.fds.service;

import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.api.StaffCaseScope;
import com.donworry.fds.api.StaffCaseScopeReader;
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
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLogin;
import com.donworry.identity.api.StaffLoginReader;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    // 열려 있으면 내보내기를 막는 경보 등급 (#651) — 주의는 기록 · 관리자 확인만, 높음부터 차단 · 정지
    private static final Set<InsiderAlertSeverity> EXPORT_BLOCKING =
            Set.of(InsiderAlertSeverity.HIGH, InsiderAlertSeverity.CRITICAL);

    private final InsiderAlertRepository alerts;
    private final InsiderAlertEvidenceRepository evidence;
    private final StaffAccessReader accessReader;
    private final ApplicationEventPublisher publisher;
    private final InsiderDetectionProperties properties;
    private final StaffLoginReader loginReader;
    private final StaffCaseScopeReader scopeReader;
    private final StaffAccountLocker locker;

    @Transactional
    public void detect(StaffAccessRecorded event) {
        // ADMIN 접근은 이 규칙의 대상이 아니다. 담당 밖 거부 · 원문 열람 · 거래 목록 조회 · 내보내기만 센다(사건 화면 열람은 세지 않음)
        if (!SECURITY_STAFF.equals(event.actorRole())) return;
        boolean outOfScope = isOutOfScopeDenial(event);
        boolean rawView = isRawView(event);
        boolean listView = isTransactionRead(event);
        if (!outOfScope && !rawView && !listView) return;

        Long staffId = event.staffId();
        alerts.lockDetectionState(staffId);

        Instant at = event.occurredAt();
        if (outOfScope) checkIns01(staffId, at);
        if (rawView) {
            checkIns03(staffId, at);
            checkIns07(event);
        }
        if (listView) {
            // 담당 범위는 지금 배정 기준이다 — 지난 시점의 배정 이력은 따로 남기지 않는다
            StaffCaseScope scope = scopeReader.findScope(staffId, at.minus(properties.scopeClosedLookback()));
            checkIns08(staffId, at, scope);
            checkIns09(staffId, at, scope);
        }
        checkIns06(staffId, at);
    }

    // 내보내기 문지기의 사전 확인 (#651) — 높음 이상 경보가 열려 있는가. 주의만 있으면 막지 않는다(정상 업무 우선, ADR-0082 3.1)
    // 호출 쪽 트랜잭션과 떼어 지금 확정된 경보를 본다 (Codex 리뷰): 호출 쪽(거래 내역 내보내기)은 다른 조회로 이미
    // 스냅샷(REPEATABLE READ)을 만든 뒤 부른다. 같은 트랜잭션에서 읽으면 그 뒤 탐지가 확정한 경보가 안 보여 반출이 통과한다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasExportBlockingAlert(Long staffId) {
        return alerts.existsByStaffIdAndStatusAndSeverityIn(staffId, InsiderAlertStatus.OPEN, EXPORT_BLOCKING);
    }

    // INS-05 경보 중 내보내기 시도 (#651) — 문지기(InsiderExportGuardService)가 부른다. 경보를 열고 계정까지 정지한다
    // 근거 = 그때 열린 높음 이상 경보들의 근거 기록. 호출 쪽 롤백(차단 응답)과 따로 확정한다. 그런 경보가 없으면 null
    // 새 트랜잭션의 첫 문장이 직원 잠금이다 — 잠금 뒤 읽는 경보가 최신 값이다(관리자 닫기 · 탐지와 같은 줄 세우기)
    // 계정 정지도 이 잠금을 잡은 채로 한다 (#670 Codex 리뷰): 내부자 사건 판정(InsiderAlertCloser)이 같은 잠금을 기다리므로,
    // 경보만 확정되고 정지는 아직인 틈에 판정이 끼어 "판정 뒤에 걸려 아무도 못 푸는 정지"가 생기지 않는다.
    // 잠금 순서는 판정과 같다(P2 직원 행 → P1 계정 행). 정지는 P1이 따로 커밋하고, 실패해도 경보는 남긴다 — 차단은 문지기가 한다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long raiseExportBlock(Long staffId, Instant at) {
        alerts.lockDetectionState(staffId);
        List<InsiderAlert> causes = alerts.findByStaffIdAndStatus(staffId, InsiderAlertStatus.OPEN).stream()
                .filter(a -> a.getRuleCode() != InsiderRule.INS_05 && EXPORT_BLOCKING.contains(a.getSeverity()))
                .toList();
        List<InsiderRule> rules = causes.stream()
                .map(InsiderAlert::getRuleCode)
                .distinct()
                .sorted()
                .toList();
        if (rules.isEmpty()) return null;
        List<Long> eventIds = evidence.findAccessEventIdsOf(
                causes.stream().map(InsiderAlert::getId).toList());
        Instant firstAt = causes.stream()
                .map(InsiderAlert::getFirstEventAt)
                .min(Comparator.naturalOrder())
                .orElse(at);
        String summary = "열린 높음 경보 %d개(%s) 상태에서 거래 내역 내보내기 시도 → 차단 · 계정 정지"
                .formatted(rules.size(), rules.stream().map(InsiderRule::code).collect(Collectors.joining(" · ")));
        Long alertId = raise(staffId, InsiderRule.INS_05, InsiderAlertSeverity.CRITICAL, summary, eventIds, firstAt, at)
                .getId();
        try {
            locker.lockBySystem(staffId, StaffLockReason.INSIDER_EXPORT_BLOCKED, alertId);
        } catch (RuntimeException e) {
            log.error("INS-05 계정 정지 실패 — 내보내기는 차단 staffId={} alertId={}", staffId, alertId, e);
        }
        return alertId;
    }

    // INS-01 담당 밖 접근 반복 — 짧은 시간에 서로 다른 사건 N개에서 막히면 주의. 같은 사건 재시도는 한 번으로 센다
    private void checkIns01(Long staffId, Instant at) {
        Duration window = properties.ins01Window();
        List<StaffAccessRecorded> denials =
                recent(staffId, InsiderRule.INS_01, at.minus(window), at.plus(window)).stream()
                        .filter(InsiderDetectionService::isOutOfScopeDenial)
                        .toList();
        List<StaffAccessRecorded> hit =
                windowMatching(denials, at, window, w -> distinctCases(w) >= properties.ins01CautionCases());
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
                windowMatching(views, at, window, w -> w.size() >= properties.ins03CautionRawViews());
        if (hit.isEmpty()) return;
        raise(
                staffId,
                InsiderRule.INS_03,
                InsiderAlertSeverity.CAUTION,
                "%d분 안에 주문자 전화 원문 %d건 열람".formatted(window.toMinutes(), hit.size()),
                hit);
    }

    // INS-07 처음 보는 브라우저 로그인 직후 원문 — 이 세션의 브라우저 · 운영체제가 지난 기간 성공 로그인에 없고
    // 로그인 뒤 짧은 시간 안이면 높음. 비교할 성공 로그인이 없으면(신규 직원 · 오래 쉰 계정) 울리지 않는다 — 휴면 계정 탈취는 놓친다
    // 요약에는 기기 이름만 넣는다. IP는 login_histories에만 있다 (ADR-0082 4절)
    private void checkIns07(StaffAccessRecorded event) {
        if (event.loginReference() == null) return;
        Long staffId = event.staffId();
        StaffLogin login = loginReader.findLogin(event.loginReference()).orElse(null);
        if (login == null || !staffId.equals(login.staffId())) return;
        Instant loginAt = login.occurredAt();
        if (event.occurredAt().isAfter(loginAt.plus(properties.ins07AfterLogin()))) return;
        // 관리자가 이 로그인 뒤에 닫았으면 같은 세션으로 다시 열지 않는다
        Instant lastClosed = alerts.findLastClosedAt(staffId, InsiderRule.INS_07);
        if (lastClosed != null && !lastClosed.isBefore(loginAt)) return;

        List<StaffLogin> before =
                loginReader.findSuccessfulLogins(staffId, loginAt.minus(properties.ins07Lookback()), loginAt).stream()
                        .filter(l -> !event.loginReference().equals(l.loginReference()))
                        .toList();
        if (before.isEmpty()) return;
        if (before.stream().anyMatch(l -> login.device().equals(l.device()))) return;

        long minutes = Duration.between(loginAt, event.occurredAt()).toMinutes();
        raise(
                staffId,
                InsiderRule.INS_07,
                InsiderAlertSeverity.HIGH,
                "처음 보는 브라우저(%s)로 로그인 %d분 뒤 원문 열람".formatted(login.device(), minutes),
                List.of(event));
    }

    // INS-08 업무 대비 대량 조회 — 거래 목록 · 내보내기로 많이 가져갔는데 담당 사건과 이어진 행이 적으면 주의, 아주 많으면 높음
    // 비율은 행 수 기준. 담당 사건 가맹점 문의로 그 가맹점을 여러 쪽 넘기는 건 이어진 조회라 울리지 않는다
    // 내보내기도 센다 (#651 Codex 리뷰) — 목록 대신 내보내기만 반복하면 대량 조회가 빠지고, 그러면 INS-05 차단도 걸리지 않는다
    private void checkIns08(Long staffId, Instant at, StaffCaseScope scope) {
        Duration window = properties.ins08Window();
        List<StaffAccessRecorded> views =
                recent(staffId, InsiderRule.INS_08, at.minus(window), at.plus(window)).stream()
                        .filter(InsiderDetectionService::isTransactionRead)
                        .toList();
        List<StaffAccessRecorded> high =
                windowMatching(views, at, window, w -> unlinkedBulk(w, scope, properties.ins08HighRows()));
        List<StaffAccessRecorded> hit = high.isEmpty()
                ? windowMatching(views, at, window, w -> unlinkedBulk(w, scope, properties.ins08CautionRows()))
                : high;
        if (hit.isEmpty()) return;
        long rows = rows(hit);
        raise(
                staffId,
                InsiderRule.INS_08,
                high.isEmpty() ? InsiderAlertSeverity.CAUTION : InsiderAlertSeverity.HIGH,
                "%d분 안에 거래 목록 · 내보내기 %d행(%d회) · 담당 사건 연결 %d%%"
                        .formatted(window.toMinutes(), rows, hit.size(), linkedRows(hit, scope) * 100 / rows),
                hit);
    }

    private boolean unlinkedBulk(List<StaffAccessRecorded> window, StaffCaseScope scope, int minRows) {
        long rows = rows(window);
        return rows > 0 && rows >= minRows && linkedRows(window, scope) < rows * properties.ins08MaxLinkedRatio();
    }

    // INS-09 무관 고객 반복 검색 — 담당 사건에 없는 서로 다른 고객 번호를 N명 이상 검색(목록 · 내보내기)하면 주의, 더 많으면 높음
    // 같은 고객을 여러 쪽 넘긴 건 1명. 요약에는 고객 번호를 넣지 않고 숫자만
    private void checkIns09(Long staffId, Instant at, StaffCaseScope scope) {
        Duration window = properties.ins09Window();
        List<StaffAccessRecorded> searches =
                recent(staffId, InsiderRule.INS_09, at.minus(window), at.plus(window)).stream()
                        .filter(v -> isTransactionRead(v) && v.customerRef() != null)
                        .filter(v -> !scope.hasCustomer(v.merchantId(), v.customerRef()))
                        .toList();
        List<StaffAccessRecorded> high =
                windowMatching(searches, at, window, w -> distinctCustomers(w) >= properties.ins09HighCustomers());
        List<StaffAccessRecorded> hit = high.isEmpty()
                ? windowMatching(searches, at, window, w -> distinctCustomers(w) >= properties.ins09CautionCustomers())
                : high;
        if (hit.isEmpty()) return;
        raise(
                staffId,
                InsiderRule.INS_09,
                high.isEmpty() ? InsiderAlertSeverity.CAUTION : InsiderAlertSeverity.HIGH,
                "%d분 안에 담당 사건과 무관한 고객 %d명 검색".formatted(window.toMinutes(), distinctCustomers(hit)),
                hit);
    }

    // 이 기록이 들어가는 창(끝이 이 기록 ~ 창 길이 뒤)을 앞에서부터 보고, 기준에 닿은 첫 창의 기록을 돌려준다
    // 먼저 일어난 기록이 늦게 커밋돼 마지막에 처리돼도 뒤 기록과 함께 센다 (#627 리뷰). 기록은 시각 순으로 온다
    private static List<StaffAccessRecorded> windowMatching(
            List<StaffAccessRecorded> records,
            Instant at,
            Duration window,
            Predicate<List<StaffAccessRecorded>> reached) {
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
            if (reached.test(inWindow)) return inWindow;
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

    private static long distinctCustomers(List<StaffAccessRecorded> records) {
        return records.stream().map(StaffAccessRecorded::customerRef).distinct().count();
    }

    private static long rows(List<StaffAccessRecorded> records) {
        return records.stream()
                .mapToLong(e -> e.itemCount() == null ? 0 : e.itemCount())
                .sum();
    }

    private static long linkedRows(List<StaffAccessRecorded> records, StaffCaseScope scope) {
        return rows(records.stream().filter(e -> isLinked(e, scope)).toList());
    }

    // 고객 번호 검색이면 담당 사건 고객일 때만, 가맹점만 골랐으면 담당 사건 가맹점일 때 이어진 조회. 필터 없는 전체 조회는 아니다
    // 주문번호 검색은 접근 기록에 남기지 않아 가맹점 필터로만 판단한다 (1 ~ 2행이라 비율 영향이 작다)
    private static boolean isLinked(StaffAccessRecorded e, StaffCaseScope scope) {
        if (e.customerRef() != null) return scope.hasCustomer(e.merchantId(), e.customerRef());
        return scope.hasMerchant(e.merchantId());
    }

    // INS-06 경보 겹침 — 같은 직원에게 서로 다른 종류의 경보가 2개 이상 열려 있으면 높음
    // INS-08 · 09는 같은 "훑기" 행동에서 함께 나오기 쉬워 한 종류로 센다 — 훑기 하나로 높음이 되지 않게 (#643)
    // INS-06이 닫힌 적 있으면 그 뒤 새 기록이 붙은 경보만 센다 — 관리자가 정상으로 본 같은 겹침으로 다시 열지 않는다 (#627 리뷰)
    // INS-05는 세지 않는다 — 이미 열린 경보의 결과라 같은 신호를 두 번 세게 된다 (#651)
    private void checkIns06(Long staffId, Instant at) {
        Instant lastClosed = alerts.findLastClosedAt(staffId, InsiderRule.INS_06);
        List<InsiderAlert> others = alerts.findByStaffIdAndStatus(staffId, InsiderAlertStatus.OPEN).stream()
                .filter(a -> a.getRuleCode() != InsiderRule.INS_06 && a.getRuleCode() != InsiderRule.INS_05)
                .filter(a -> lastClosed == null || a.getLastEventAt().isAfter(lastClosed))
                .toList();
        List<InsiderRule> rules = others.stream()
                .map(InsiderAlert::getRuleCode)
                .distinct()
                .sorted()
                .toList();
        long signals = rules.stream()
                .map(InsiderDetectionService::signalGroup)
                .distinct()
                .count();
        if (signals < 2) return;
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
    private InsiderAlert raise(
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
        return alert;
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

    // 거래 목록 조회 · 허용된 내보내기 — 둘 다 행 수 · 거른 가맹점 · 고객 번호가 같은 모양으로 기록된다 (#615 · #651)
    private static boolean isTransactionRead(StaffAccessRecorded e) {
        return e.result() == StaffAccessResult.ALLOWED
                && (e.accessType() == StaffAccessType.TRANSACTION_LIST_VIEW
                        || e.accessType() == StaffAccessType.TRANSACTION_EXPORT);
    }

    private static String signalGroup(InsiderRule rule) {
        return rule == InsiderRule.INS_08 || rule == InsiderRule.INS_09 ? "LIST_LOOKUP" : rule.name();
    }
}
