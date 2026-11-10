package com.donworry.investigation.service;

import com.donworry.fds.api.InsiderAlertEntry;
import com.donworry.fds.api.InsiderAlertMonitorReader;
import com.donworry.fds.api.InsiderRule;
import com.donworry.identity.api.LoginAttackCriteria;
import com.donworry.identity.api.LoginTrafficCount;
import com.donworry.identity.api.LoginTrafficMinute;
import com.donworry.identity.api.LoginTrafficReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.investigation.dto.LoginSecurityAlertResponse;
import com.donworry.investigation.dto.LoginSecurityMinuteResponse;
import com.donworry.investigation.dto.LoginSecurityResponse;
import com.donworry.investigation.dto.LoginSecurityStaffLoginResponse;
import com.donworry.investigation.dto.LoginSecurityState;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseStatus;
import com.donworry.investigation.repository.InsiderCaseRepository;
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
    // 1분 막대 개수 · 보안 직원 로그인 목록 범위 · 경보 목록 범위
    static final int MINUTES = 15;
    static final Duration STAFF_LOGIN_LOOKBACK = Duration.ofHours(1);
    static final int MAX_STAFF_LOGINS = 50;
    static final Duration ALERT_LOOKBACK = Duration.ofHours(24);
    private static final List<InsiderRule> LOGIN_RULES = List.of(InsiderRule.INS_10, InsiderRule.INS_05);

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
                .minutes(minutes(now))
                .staffLogins(
                        traffic.findSecurityStaffLogins(now.minus(STAFF_LOGIN_LOOKBACK), now, MAX_STAFF_LOGINS).stream()
                                .map(e -> LoginSecurityStaffLoginResponse.builder()
                                        .staffId(e.staffId())
                                        .staffName(names.get(e.staffId()))
                                        .eventType(e.eventType().name())
                                        .occurredAt(e.occurredAt())
                                        .device(e.device())
                                        .build())
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

    // 주의는 화면 안내용이다 — 시도가 적을 때 오타 몇 번으로 띠가 바뀌지 않게 기준 시도 수의 절반부터 본다
    static LoginSecurityState state(LoginAttackCriteria criteria, LoginTrafficCount recent) {
        if (criteria.isUnderAttack(recent.attempts(), recent.failures())) return LoginSecurityState.UNDER_ATTACK;
        boolean highRatio =
                recent.attempts() > 0 && (double) recent.failures() / recent.attempts() >= criteria.failureRatio();
        return highRatio && recent.attempts() * 2 >= criteria.minAttempts()
                ? LoginSecurityState.CAUTION
                : LoginSecurityState.NORMAL;
    }

    // 지금 분을 포함한 최근 MINUTES개 분. 빈 분은 0으로 채운다
    private List<LoginSecurityMinuteResponse> minutes(Instant now) {
        Instant end = now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
        Instant start = end.minus(Duration.ofMinutes(MINUTES));
        Map<Instant, LoginTrafficMinute> counted = traffic.countPerMinute(start, end).stream()
                .collect(Collectors.toMap(LoginTrafficMinute::minuteStart, Function.identity()));
        List<LoginSecurityMinuteResponse> result = new ArrayList<>(MINUTES);
        for (Instant minute = start; minute.isBefore(end); minute = minute.plus(Duration.ofMinutes(1))) {
            LoginTrafficMinute m = counted.get(minute);
            result.add(LoginSecurityMinuteResponse.builder()
                    .minuteStart(minute)
                    .attempts(m == null ? 0 : m.attempts())
                    .failures(m == null ? 0 : m.failures())
                    .unknownAccountFailures(m == null ? 0 : m.unknownAccountFailures())
                    .build());
        }
        return result;
    }

    private static int percent(long part, long whole) {
        return whole == 0 ? 0 : (int) Math.round(part * 100.0 / whole);
    }
}
