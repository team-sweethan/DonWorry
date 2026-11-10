package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertEntry;
import com.donworry.fds.api.InsiderAlertMonitorReader;
import com.donworry.fds.api.InsiderAlertSeverity;
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
import com.donworry.investigation.dto.LoginSecurityPeriodResponse;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 관리자 로그인 보안 대시보드 (#770) — P1 집계 · P2 경보는 공개 창구 대역, 경보 ↔ 사건 연결은 저장소 대역으로 정한다
@ExtendWith(MockitoExtension.class)
class LoginSecurityServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-10T07:00:00Z");
    private static final Long STAFF_ID = 31L;

    @Mock
    SecurityStaffGuard guard;

    @Mock
    LoginTrafficReader traffic;

    @Mock
    InsiderAlertMonitorReader alertReader;

    @Mock
    SecurityStaffDirectory staffDirectory;

    @Mock
    InsiderCaseAlertRepository caseAlerts;

    private LoginSecurityService service;

    @BeforeEach
    void setUp() {
        service = new LoginSecurityService(guard, traffic, alertReader, staffDirectory, caseAlerts);
        when(traffic.criteria()).thenReturn(new LoginAttackCriteria(Duration.ofMinutes(5), 30, 0.3));
        when(traffic.count(any(), any())).thenReturn(new LoginTrafficCount(0, 0, 0));
        when(staffDirectory.securityStaff()).thenReturn(List.of(new SecurityStaffEntry(STAFF_ID, "한지우", true)));
    }

    // 같은 직원의 이전 사건이 판정으로 닫히고 새 사건이 열려도, 지난 경보의 [사건 보기]는 그 경보가 속한 사건으로 간다
    // (직원의 지금 열린 사건을 붙이면 닫힌 경보가 무관한 새 사건으로 이어진다 — #777 Codex 리뷰)
    @Test
    void dashboard_whenEarlierCaseClosedAndNewCaseOpened_linksEachAlertToItsOwnCase() {
        when(alertReader.findAlertsOpenedBetween(anyCollection(), any(), any()))
                .thenReturn(List.of(ins10(5L, false, NOW.minus(Duration.ofHours(3))), ins10(9L, true, NOW)));
        when(caseAlerts.findByAlertIdIn(anyCollection())).thenReturn(List.of(link(2L, 5L), link(3L, 9L)));

        var response = service.dashboard(NOW);

        assertThat(response.alerts())
                .extracting(LoginSecurityAlertResponse::alertId, LoginSecurityAlertResponse::insiderCaseId)
                .containsExactly(tuple(5L, 2L), tuple(9L, 3L));
    }

    // 탈취 의심 표시는 경보가 실제로 탐지한 로그인(첫 · 마지막 기록 시각)에만 붙는다 (#777 Codex 리뷰)
    // — 경보 앞뒤 시간으로만 보면 그 사이 평소 브라우저로 들어온 진짜 직원이 빨갛게 보이고, 늦게 묶인 의심 로그인은 빠진다
    @Test
    void dashboard_whenUsualLoginNearSuspectedAlert_marksOnlyDetectedLogins() {
        Instant detected = NOW.minus(Duration.ofMinutes(6));
        Instant usual = detected.plusSeconds(10);
        Instant laterDetected = NOW.minus(Duration.ofMinutes(1));
        InsiderAlertEntry alert = InsiderAlertEntry.builder()
                .alertId(7L)
                .staffId(STAFF_ID)
                .rule(InsiderRule.INS_10)
                .severity(InsiderAlertSeverity.HIGH)
                .open(true)
                .summary("로그인 공격 중 처음 보는 브라우저 로그인")
                .createdAt(detected.plusMillis(200))
                .firstEventAt(detected)
                .lastEventAt(laterDetected)
                .build();
        when(alertReader.findAlertsOpenedBetween(anyCollection(), any(), any())).thenReturn(List.of(alert));
        when(caseAlerts.findByAlertIdIn(anyCollection())).thenReturn(List.of(link(4L, 7L)));
        when(traffic.findRecentAttempts(any(), any(), anyInt()))
                .thenReturn(List.of(
                        attempt(laterDetected, "Chrome · Windows"),
                        attempt(usual, "Edge · Windows"),
                        attempt(detected, "기타 브라우저 · 기타 운영체제")));

        var response = service.dashboard(NOW);

        assertThat(response.attempts())
                .extracting(LoginSecurityAttemptResponse::device, LoginSecurityAttemptResponse::suspected)
                .containsExactly(
                        tuple("Chrome · Windows", true),
                        tuple("Edge · Windows", false),
                        tuple("기타 브라우저 · 기타 운영체제", true));
    }

    // 그래프 ① 경계 진입은 실패가 기준(5분 30건 · 30%)을 처음 넘은 초다 — 10초 칸으로 어림하면 발표 설명과 시각이 어긋난다 (#788)
    @Test
    void dashboard_whenAttackStartsWithinRange_returnsPeriodFromCrossingSecond() {
        perSecond(failuresEverySecond(at("06:58:00"), 40));

        var response = service.dashboard(NOW);

        assertThat(response.attackPeriods()).containsExactly(new LoginSecurityPeriodResponse(at("06:58:29"), null));
    }

    // 기준 아래로 내려간 초에 구간을 닫는다 — 닫지 않으면 끝난 공격이 계속 경계 음영으로 남는다
    @Test
    void dashboard_whenAttackEnded_returnsClosedPeriod() {
        List<LoginTrafficBucket> buckets = new ArrayList<>(failuresEverySecond(at("06:56:00"), 40));
        buckets.add(LoginTrafficBucket.builder()
                .start(at("06:57:00"))
                .attempts(100)
                .failures(0)
                .unknownAccountFailures(0)
                .build());
        perSecond(buckets);

        var response = service.dashboard(NOW);

        assertThat(response.attackPeriods())
                .containsExactly(new LoginSecurityPeriodResponse(at("06:56:29"), at("06:57:00")));
    }

    // 그래프 범위(06:55:10부터) 앞에서 이미 경계였으면 진입 시각이 없다 — 범위 첫 초를 진입으로 찍으면 가짜 ①이 생긴다
    @Test
    void dashboard_whenAttackStartedBeforeRange_doesNotInventEntryTime() {
        perSecond(failuresEverySecond(at("06:54:00"), 40));

        var response = service.dashboard(NOW);

        assertThat(response.attackPeriods()).containsExactly(new LoginSecurityPeriodResponse(null, at("06:59:10")));
    }

    // 1초 칸 집계만 대역으로 돌려준다 — 꺾은선(10초 칸) 호출은 빈 목록
    private void perSecond(List<LoginTrafficBucket> buckets) {
        when(traffic.countPerBucket(any(), any(), any()))
                .thenAnswer(call -> Duration.ofSeconds(1).equals(call.getArgument(2)) ? buckets : List.of());
    }

    private static List<LoginTrafficBucket> failuresEverySecond(Instant from, int count) {
        List<LoginTrafficBucket> buckets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            buckets.add(LoginTrafficBucket.builder()
                    .start(from.plusSeconds(i))
                    .attempts(1)
                    .failures(1)
                    .unknownAccountFailures(1)
                    .build());
        }
        return buckets;
    }

    private static Instant at(String utcTime) {
        return Instant.parse("2026-11-10T" + utcTime + "Z");
    }

    private static LoginAttempt attempt(Instant at, String device) {
        return LoginAttempt.builder()
                .occurredAt(at)
                .success(true)
                .userId(STAFF_ID)
                .role("SECURITY_STAFF")
                .device(device)
                .build();
    }

    private static InsiderAlertEntry ins10(Long alertId, boolean open, Instant createdAt) {
        return InsiderAlertEntry.builder()
                .alertId(alertId)
                .staffId(STAFF_ID)
                .rule(InsiderRule.INS_10)
                .severity(InsiderAlertSeverity.HIGH)
                .open(open)
                .summary("로그인 공격 중 처음 보는 브라우저 로그인")
                .createdAt(createdAt)
                .closedAt(open ? null : createdAt.plus(Duration.ofHours(1)))
                .build();
    }

    private static InsiderCaseAlert link(Long insiderCaseId, Long alertId) {
        return InsiderCaseAlert.link(
                insiderCaseId, alertId, InsiderRule.INS_10, InsiderAlertSeverity.HIGH, "탈취 의심", NOW, NOW);
    }
}
