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
import com.donworry.investigation.dto.LoginSecurityResponse;
import com.donworry.investigation.dto.LoginSecurityState;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseStatus;
import com.donworry.investigation.repository.InsiderCaseRepository;
import java.time.Duration;
import java.time.Instant;
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
    static final int MAX_ATTEMPTS = 40;
    static final Duration ATTEMPT_LOOKBACK = Duration.ofMinutes(10);
    static final Duration ALERT_LOOKBACK = Duration.ofHours(24);
    // 탈취 의심 경보는 로그인 몇 초 뒤(커밋 뒤 알림) 열린다 — 이 안이면 그 로그인 줄에 표시한다
    static final Duration SUSPECT_MATCH = Duration.ofMinutes(2);
    private static final List<InsiderRule> LOGIN_RULES = List.of(InsiderRule.INS_10, InsiderRule.INS_05);
    private static final String SECURITY_STAFF = "SECURITY_STAFF";

    private final SecurityStaffGuard guard;
    private final LoginTrafficReader traffic;
    private final InsiderAlertMonitorReader alertReader;
    private final SecurityStaffDirectory staffDirectory;
    private final InsiderCaseRepository insiderCases;

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
        // 사람당 열린 내부자 사건은 1개 (#670 uk_insider_cases_open)
        Map<Long, Long> openCases = insiderCases.findByStatusOrderByIdAsc(InsiderCaseStatus.OPEN).stream()
                .collect(Collectors.toMap(InsiderCase::getStaffId, InsiderCase::getId, (a, b) -> a));
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
                                .insiderCaseId(openCases.get(a.staffId()))
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
        boolean suspected = staff
                && a.success()
                && alerts.stream()
                        .anyMatch(alert -> alert.rule() == InsiderRule.INS_10
                                && alert.staffId().equals(a.userId())
                                && !alert.createdAt().isBefore(a.occurredAt())
                                && !alert.createdAt().isAfter(a.occurredAt().plus(SUSPECT_MATCH)));
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

    // 지금 칸을 포함해 탐지 구간(window)만큼 BUCKET 칸. 빈 칸은 0으로 채운다. 칸 경계는 10초 단위(UTC)에 맞춘다
    private List<LoginSecurityBucketResponse> buckets(Instant now, Duration window) {
        long step = BUCKET.toMillis();
        Instant end = Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), step) * step + step);
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
