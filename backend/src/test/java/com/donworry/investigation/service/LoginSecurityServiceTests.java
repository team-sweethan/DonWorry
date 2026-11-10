package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertEntry;
import com.donworry.fds.api.InsiderAlertMonitorReader;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.identity.api.LoginAttackCriteria;
import com.donworry.identity.api.LoginTrafficCount;
import com.donworry.identity.api.LoginTrafficReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.investigation.dto.LoginSecurityAlertResponse;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import java.time.Duration;
import java.time.Instant;
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
