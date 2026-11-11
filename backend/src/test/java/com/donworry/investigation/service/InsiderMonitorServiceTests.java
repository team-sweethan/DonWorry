package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertEntry;
import com.donworry.fds.api.InsiderAlertMonitorReader;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.api.StaffCaseScope;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessResult;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLockRecord;
import com.donworry.identity.api.StaffLoginEvent;
import com.donworry.identity.api.StaffLoginEventType;
import com.donworry.identity.api.StaffLoginReader;
import com.donworry.investigation.dto.InsiderMonitorEventResponse;
import com.donworry.investigation.dto.InsiderMonitorStage;
import com.donworry.investigation.dto.InsiderMonitorTimelineResponse;
import com.donworry.investigation.repository.InsiderCaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

// 내부자 관제 한 화면 (#679) — 로그인 · 접근 · 경보 · 정지는 P1 · P2 공개 창구 대역으로 정한다
@ExtendWith(MockitoExtension.class)
class InsiderMonitorServiceTests {

    private static final Long STAFF_ID = 21L;
    private static final Long MERCHANT_ID = 3L;
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    @Mock
    SecurityStaffGuard guard;

    @Mock
    SecurityStaffDirectory staffDirectory;

    @Mock
    InsiderAlertMonitorReader alertReader;

    @Mock
    InsiderCaseRepository insiderCases;

    @Mock
    StaffAccountLocker locker;

    @Mock
    StaffLoginReader loginReader;

    @Mock
    StaffAccessReader accessReader;

    @Mock
    StaffCaseScopeReader scopeReader;

    @Mock
    MerchantDirectoryReader merchantDirectory;

    @InjectMocks
    InsiderMonitorService service;

    @BeforeEach
    void staffExists() {
        lenient()
                .when(staffDirectory.securityStaff())
                .thenReturn(List.of(new SecurityStaffEntry(STAFF_ID, "보안직원A", true)));
        lenient().when(insiderCases.findByStaffIdAndStatus(any(), any())).thenReturn(Optional.empty());
        lenient().when(loginReader.findLoginEvents(eq(STAFF_ID), any(), any())).thenReturn(List.of());
        lenient().when(accessReader.findRecent(eq(STAFF_ID), any(), any())).thenReturn(List.of());
        lenient().when(alertReader.findAlerts(eq(STAFF_ID), any(), any())).thenReturn(List.of());
        lenient().when(locker.findLocks(STAFF_ID)).thenReturn(List.of());
        lenient().when(merchantDirectory.findAll()).thenReturn(List.of());
    }

    // 관제 응답에는 접근 기록의 고객 번호가 들어가지 않는다 (원문 칸은 접근 기록에 처음부터 없다)
    @Test
    void timeline_containsNoRawValueOrCustomerRef() {
        String customerRef = "3:778899";
        when(accessReader.findRecent(eq(STAFF_ID), any(), any()))
                .thenReturn(List.of(access(1L, NOW.minusSeconds(60), customerRef)));
        when(scopeReader.findScope(eq(STAFF_ID), any())).thenReturn(new StaffCaseScope(Set.of(), Set.of()));

        InsiderMonitorTimelineResponse timeline = service.timeline(STAFF_ID, NOW);

        String json = JsonMapper.builder().build().writeValueAsString(timeline);
        assertThat(timeline.events()).hasSize(1);
        assertThat(json).doesNotContain("778899").doesNotContain("customerRef");
    }

    // 사건 없이 내보내기로 바로 정지된 직원도 "정지" 단계다
    @Test
    void stage_lockedStaff_isBlockedEvenWithoutOpenCase() {
        when(locker.findLocks(STAFF_ID))
                .thenReturn(List.of(StaffLockRecord.builder()
                        .lockId(1L)
                        .staffId(STAFF_ID)
                        .reason(StaffLockReason.INSIDER_EXPORT_BLOCKED)
                        .sourceAlertId(500L)
                        .lockedAt(NOW.minusSeconds(30))
                        .open(true)
                        .build()));

        InsiderMonitorTimelineResponse timeline = service.timeline(STAFF_ID, NOW);

        assertThat(timeline.staff().openInsiderCaseId()).isNull();
        assertThat(timeline.staff().stage()).isEqualTo(InsiderMonitorStage.BLOCKED);
    }

    // 타임라인은 마지막 로그인 성공 30분 전부터 — 그 직전 비밀번호 실패는 보이고, 그보다 앞선 로그인은 빠진다
    @Test
    void timeline_startsAtLatestLoginIncludingPrecedingFailures() {
        Instant oldLogin = NOW.minusSeconds(5 * 3600);
        Instant failure = NOW.minusSeconds(3600 + 600);
        Instant latestLogin = NOW.minusSeconds(3600);
        when(loginReader.findLoginEvents(eq(STAFF_ID), any(), eq(NOW)))
                .thenReturn(List.of(
                        login(StaffLoginEventType.LOGIN_SUCCESS, oldLogin),
                        login(StaffLoginEventType.LOGIN_FAILURE, failure),
                        login(StaffLoginEventType.LOGIN_SUCCESS, latestLogin)));

        InsiderMonitorTimelineResponse timeline = service.timeline(STAFF_ID, NOW);

        assertThat(timeline.from()).isEqualTo(latestLogin.minusSeconds(30 * 60));
        assertThat(timeline.events())
                .extracting(InsiderMonitorEventResponse::type, InsiderMonitorEventResponse::occurredAt)
                .containsExactly(tuple("LOGIN_FAILURE", failure), tuple("LOGIN_SUCCESS", latestLogin));
    }

    // 담당 사건 고객 검색은 RELATED, 담당과 무관한 고객 검색은 UNRELATED로 표시한다
    @Test
    void timeline_marksUnrelatedCustomerSearch() {
        when(accessReader.findRecent(eq(STAFF_ID), any(), any()))
                .thenReturn(
                        List.of(access(1L, NOW.minusSeconds(120), "3:100"), access(2L, NOW.minusSeconds(60), "3:999")));
        when(scopeReader.findScope(eq(STAFF_ID), any()))
                .thenReturn(new StaffCaseScope(
                        Set.of(MERCHANT_ID), Set.of(new StaffCaseScope.CaseCustomer(MERCHANT_ID, "3:100"))));

        InsiderMonitorTimelineResponse timeline = service.timeline(STAFF_ID, NOW);

        assertThat(timeline.events())
                .extracting(InsiderMonitorEventResponse::customerScope)
                .containsExactly("RELATED", "UNRELATED");
    }

    // 같은 초 안에서는 거부된 접근 → 경보 → 정지 순서로 보인다 (저장 시각은 정지가 접근보다 먼저여도)
    @Test
    void timeline_sameSecond_ordersAccessAlertLock() {
        Instant second = NOW.minusSeconds(60);
        when(locker.findLocks(STAFF_ID))
                .thenReturn(List.of(StaffLockRecord.builder()
                        .lockId(1L)
                        .staffId(STAFF_ID)
                        .reason(StaffLockReason.INSIDER_EXPORT_BLOCKED)
                        .sourceAlertId(500L)
                        .lockedAt(second.plusMillis(100))
                        .open(true)
                        .build()));
        when(alertReader.findAlerts(eq(STAFF_ID), any(), any()))
                .thenReturn(List.of(InsiderAlertEntry.builder()
                        .alertId(500L)
                        .staffId(STAFF_ID)
                        .rule(InsiderRule.INS_05)
                        .severity(InsiderAlertSeverity.CRITICAL)
                        .open(true)
                        .summary("높음 경보 중 내보내기 시도")
                        .createdAt(second.plusMillis(200))
                        .build()));
        when(accessReader.findRecent(eq(STAFF_ID), any(), any()))
                .thenReturn(List.of(StaffAccessRecorded.builder()
                        .eventId(9L)
                        .staffId(STAFF_ID)
                        .accessType(StaffAccessType.TRANSACTION_EXPORT)
                        .result(StaffAccessResult.DENIED)
                        .occurredAt(second.plusMillis(300))
                        .build()));

        InsiderMonitorTimelineResponse timeline = service.timeline(STAFF_ID, NOW);

        assertThat(timeline.events())
                .extracting(InsiderMonitorEventResponse::kind)
                .containsExactly("ACCESS", "ALERT", "LOCK");
    }

    private static StaffLoginEvent login(StaffLoginEventType type, Instant at) {
        return StaffLoginEvent.builder()
                .staffId(STAFF_ID)
                .eventType(type)
                .occurredAt(at)
                .clientIp("10.0.0.7")
                .device("Edge · Windows")
                .build();
    }

    private static StaffAccessRecorded access(Long eventId, Instant at, String customerRef) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF_ID)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.TRANSACTION_LIST_VIEW)
                .result(StaffAccessResult.ALLOWED)
                .merchantId(MERCHANT_ID)
                .customerRef(customerRef)
                .itemCount(20)
                .occurredAt(at)
                .build();
    }
}
