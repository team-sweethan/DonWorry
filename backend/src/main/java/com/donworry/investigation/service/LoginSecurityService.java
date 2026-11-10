package com.donworry.investigation.service;

import com.donworry.fds.api.InsiderAlertEntry;
import com.donworry.fds.api.InsiderAlertMonitorReader;
import com.donworry.fds.api.InsiderRule;
import com.donworry.identity.api.LoginAttackCriteria;
import com.donworry.identity.api.LoginAttempt;
import com.donworry.identity.api.LoginTrafficBucket;
import com.donworry.identity.api.LoginTrafficCount;
import com.donworry.identity.api.LoginTrafficReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.investigation.dto.LoginSecurityAlertResponse;
import com.donworry.investigation.dto.LoginSecurityAttemptResponse;
import com.donworry.investigation.dto.LoginSecurityBucketResponse;
import com.donworry.investigation.dto.LoginSecurityPeriodResponse;
import com.donworry.investigation.dto.LoginSecurityResponse;
import com.donworry.investigation.dto.LoginSecurityState;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 로그인 보안 대시보드 (#770, ADR-0082) — P1 로그인 집계와 P2 내부자 경보(INS-10 · INS-05)를 한 화면으로 모은다. 읽기만
// 경계 상태를 저장하지 않는다 — 탐지처럼 부를 때마다 최근 구간을 다시 센다
@Service
@RequiredArgsConstructor
public class LoginSecurityService {
    // 꺾은선 칸 길이(구간은 탐지 window) · 실시간 시도 줄 수와 범위 · 경보 목록 범위
    static final Duration BUCKET = Duration.ofSeconds(10);
    // 경계 구간을 셀 칸 — 화면이 시:분:초로 보이므로 1초 (#788)
    static final Duration PERIOD_STEP = Duration.ofSeconds(1);
    static final int MAX_ATTEMPTS = 40;
    static final Duration ATTEMPT_LOOKBACK = Duration.ofMinutes(10);
    static final Duration ALERT_LOOKBACK = Duration.ofHours(24);
    private static final List<InsiderRule> LOGIN_RULES = List.of(InsiderRule.INS_10, InsiderRule.INS_05);
    private static final String SECURITY_STAFF = "SECURITY_STAFF";

    private final SecurityStaffGuard guard;
    private final LoginTrafficReader traffic;
    private final InsiderAlertMonitorReader alertReader;
    private final SecurityStaffDirectory staffDirectory;
    private final InsiderCaseAlertRepository caseAlerts;

    @Transactional(readOnly = true)
    public LoginSecurityResponse dashboard() {
        guard.requireAdmin();
        return dashboard(Instant.now());
    }

    LoginSecurityResponse dashboard(Instant now) {
        LoginAttackCriteria criteria = traffic.criteria();
        LoginTrafficCount recent = traffic.count(now.minus(criteria.window()), now);
        Map<Long, String> names = staffDirectory.securityStaff().stream()
                .collect(Collectors.toMap(SecurityStaffEntry::userId, SecurityStaffEntry::displayName, (a, b) -> a));
        List<InsiderAlertEntry> alerts = loginAlerts(now);
        // 경보가 실제로 묶인 사건 — 직원의 지금 열린 사건을 붙이면 판정으로 닫힌 지난 경보가 새 사건으로 이어진다 (#777 Codex 리뷰)
        Map<Long, Long> linkedCases = alerts.isEmpty()
                ? Map.of()
                : caseAlerts
                        .findByAlertIdIn(
                                alerts.stream().map(InsiderAlertEntry::alertId).toList())
                        .stream()
                        .collect(Collectors.toMap(
                                InsiderCaseAlert::getAlertId, InsiderCaseAlert::getInsiderCaseId, (a, b) -> a));
        return LoginSecurityResponse.builder()
                .generatedAt(now)
                .state(state(criteria, recent))
                .windowMinutes((int) criteria.window().toMinutes())
                .minAttempts(criteria.minAttempts())
                .failureRatePercentThreshold((int) Math.round(criteria.failureRatio() * 100))
                .recentAttempts(recent.attempts())
                .recentFailures(recent.failures())
                .recentUnknownAccountFailures(recent.unknownAccountFailures())
                .recentFailureRatePercent(percent(recent.failures(), recent.attempts()))
                .suspectedTakeoverCount(alerts.stream()
                        .filter(a -> a.rule() == InsiderRule.INS_10)
                        .count())
                .bucketSeconds((int) BUCKET.toSeconds())
                .buckets(buckets(now, criteria.window()))
                .attackPeriods(attackPeriods(now, criteria))
                .attempts(traffic.findRecentAttempts(now.minus(ATTEMPT_LOOKBACK), now, MAX_ATTEMPTS).stream()
                        .map(a -> attempt(a, names, alerts))
                        .toList())
                .alerts(alerts.stream()
                        .map(a -> LoginSecurityAlertResponse.builder()
                                .alertId(a.alertId())
                                .staffId(a.staffId())
                                .staffName(names.get(a.staffId()))
                                .ruleCode(a.rule().code())
                                .ruleLabel(a.rule().label())
                                .severity(a.severity().name())
                                .summary(a.summary())
                                .open(a.open())
                                .createdAt(a.createdAt())
                                .insiderCaseId(linkedCases.get(a.alertId()))
                                .build())
                        .toList())
                .build();
    }

    // INS-05(내보내기 차단)는 같은 직원에게 INS-10(탈취 의심)이 있을 때만 — 다른 내부자 경보로 막힌 반출은 이 화면 몫이 아니다 (#770 중간 점검)
    private List<InsiderAlertEntry> loginAlerts(Instant now) {
        List<InsiderAlertEntry> found =
                alertReader.findAlertsOpenedBetween(LOGIN_RULES, now.minus(ALERT_LOOKBACK), now);
        Set<Long> suspected = found.stream()
                .filter(a -> a.rule() == InsiderRule.INS_10)
                .map(InsiderAlertEntry::staffId)
                .collect(Collectors.toSet());
        return found.stream()
                .filter(a -> a.rule() == InsiderRule.INS_10 || suspected.contains(a.staffId()))
                .toList();
    }

    // 보안 직원이 아닌 계정은 이름 없이 종류만 — 이 화면은 보안 직원 탈취를 보는 곳이다
    private static LoginSecurityAttemptResponse attempt(
            LoginAttempt a, Map<Long, String> names, List<InsiderAlertEntry> alerts) {
        boolean staff = SECURITY_STAFF.equals(a.role());
        // 경보가 실제로 탐지한 로그인(첫 · 마지막 기록 시각과 같은 시각)에만 붙인다 (#777 Codex 리뷰)
        // 한계: 열린 경보가 여러 번 갱신되면 그 사이에 걸린 로그인 시각은 경보에 남지 않아 표시되지 않는다
        boolean suspected = staff
                && a.success()
                && alerts.stream()
                        .anyMatch(alert -> alert.rule() == InsiderRule.INS_10
                                && alert.staffId().equals(a.userId())
                                && (a.occurredAt().equals(alert.firstEventAt())
                                        || a.occurredAt().equals(alert.lastEventAt())));
        return LoginSecurityAttemptResponse.builder()
                .occurredAt(a.occurredAt())
                .success(a.success())
                .account(a.role() == null ? "UNKNOWN" : a.role())
                .staffId(staff ? a.userId() : null)
                .staffName(staff ? names.get(a.userId()) : null)
                .device(a.device())
                .suspected(suspected)
                .build();
    }

    // 주의는 화면 안내용이다 — 시도가 적을 때 오타 몇 번으로 띠가 바뀌지 않게 기준 시도 수의 절반부터 본다
    static LoginSecurityState state(LoginAttackCriteria criteria, LoginTrafficCount recent) {
        if (criteria.isUnderAttack(recent.attempts(), recent.failures())) return LoginSecurityState.UNDER_ATTACK;
        boolean highRatio =
                recent.attempts() > 0 && (double) recent.failures() / recent.attempts() >= criteria.failureRatio();
        return highRatio && recent.attempts() * 2 >= criteria.minAttempts()
                ? LoginSecurityState.CAUTION
                : LoginSecurityState.NORMAL;
    }

    // 꺾은선 범위의 끝 — 지금 칸의 끝(10초 단위, UTC). 시작은 여기서 window만큼 앞
    private static Instant chartEnd(Instant now) {
        long step = BUCKET.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), step) * step + step);
    }

    // 꺾은선 범위 안 경계 구간 (#788). 범위 첫 초부터 지금 초까지 초마다 "그 초까지 window 동안" 시도 · 실패를 탐지 기준으로 판정한다
    // 범위 첫 초를 판정하려면 window만큼 앞 기록이 필요하고, 첫 초가 진입인지 알려면 그 1초 앞도 판정해야 해서 window + 1초 앞부터 센다
    // 한계: 초 단위 칸이라 상태 띠(정확한 (now - window, now] 구간)와 경계선에서 최대 1초 어긋날 수 있다
    private List<LoginSecurityPeriodResponse> attackPeriods(Instant now, LoginAttackCriteria criteria) {
        int windowSteps = (int) criteria.window().dividedBy(PERIOD_STEP);
        Instant rangeStart = chartEnd(now).minus(criteria.window());
        Instant from = rangeStart.minus(criteria.window()).minus(PERIOD_STEP);
        Instant to = now.truncatedTo(ChronoUnit.SECONDS).plus(PERIOD_STEP);
        int steps = (int) Duration.between(from, to).dividedBy(PERIOD_STEP);
        long[] attempts = new long[steps];
        long[] failures = new long[steps];
        for (LoginTrafficBucket b : traffic.countPerBucket(from, to, PERIOD_STEP)) {
            int i = (int) Duration.between(from, b.start()).dividedBy(PERIOD_STEP);
            if (i < 0 || i >= steps) continue;
            attempts[i] = b.attempts();
            failures[i] = b.failures();
        }
        // 누적합 — sum[i]는 0 ~ i-1 칸의 합
        long[] attemptSum = new long[steps + 1];
        long[] failureSum = new long[steps + 1];
        for (int i = 0; i < steps; i++) {
            attemptSum[i + 1] = attemptSum[i] + attempts[i];
            failureSum[i + 1] = failureSum[i] + failures[i];
        }
        int first = windowSteps + 1;
        List<LoginSecurityPeriodResponse> periods = new ArrayList<>();
        boolean inAttack = underAttack(criteria, attemptSum, failureSum, first - 1, windowSteps);
        Instant start = null;
        for (int i = first; i < steps; i++) {
            boolean attack = underAttack(criteria, attemptSum, failureSum, i, windowSteps);
            Instant at = from.plus(PERIOD_STEP.multipliedBy(i));
            if (attack && !inAttack) start = at;
            if (!attack && inAttack) {
                periods.add(new LoginSecurityPeriodResponse(start, at));
                start = null;
            }
            inAttack = attack;
        }
        if (inAttack) periods.add(new LoginSecurityPeriodResponse(start, null));
        return periods;
    }

    // i번째 초까지 window 칸(i - windowSteps + 1 ~ i)이 경계 기준을 넘는지
    private static boolean underAttack(
            LoginAttackCriteria criteria, long[] attemptSum, long[] failureSum, int i, int windowSteps) {
        int lo = Math.max(0, i - windowSteps + 1);
        return criteria.isUnderAttack(attemptSum[i + 1] - attemptSum[lo], failureSum[i + 1] - failureSum[lo]);
    }

    // 지금 칸을 포함해 탐지 구간(window)만큼 BUCKET 칸. 빈 칸은 0으로 채운다. 칸 경계는 10초 단위(UTC)에 맞춘다
    private List<LoginSecurityBucketResponse> buckets(Instant now, Duration window) {
        Instant end = chartEnd(now);
        Instant start = end.minus(window);
        Map<Instant, LoginTrafficBucket> counted = traffic.countPerBucket(start, end, BUCKET).stream()
                .collect(Collectors.toMap(LoginTrafficBucket::start, Function.identity(), (a, b) -> a));
        List<LoginSecurityBucketResponse> result = new ArrayList<>();
        for (Instant at = start; at.isBefore(end); at = at.plus(BUCKET)) {
            LoginTrafficBucket b = counted.get(at);
            result.add(LoginSecurityBucketResponse.builder()
                    .start(at)
                    .attempts(b == null ? 0 : b.attempts())
                    .failures(b == null ? 0 : b.failures())
                    .unknownAccountFailures(b == null ? 0 : b.unknownAccountFailures())
                    .build());
        }
        return result;
    }

    private static int percent(long part, long whole) {
        return whole == 0 ? 0 : (int) Math.round(part * 100.0 / whole);
    }
}
