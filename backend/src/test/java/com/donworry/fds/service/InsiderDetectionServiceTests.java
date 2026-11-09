package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.api.StaffCaseScope;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.fds.dto.InsiderAlertDetailResponse;
import com.donworry.fds.entity.FdsRulePolicy;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertEvidence;
import com.donworry.fds.entity.InsiderAlertStatus;
import com.donworry.fds.repository.FdsRulePolicyRepository;
import com.donworry.fds.repository.InsiderAlertEvidenceRepository;
import com.donworry.fds.repository.InsiderAlertRepository;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessFieldType;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessResult;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLogin;
import com.donworry.identity.api.StaffLoginReader;
import com.donworry.identity.api.StaffSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

// 내부자 탐지 (#516) — 승인된 테스트 목록만 (노트 issue-516 "승인된 테스트 목록")
// 저장소는 메모리 목록으로 흉내 낸다 — 같은 직원 · 규칙의 열린 경보, 근거 중복을 실제처럼 확인하려고
// 행 잠금 · UNIQUE · 생성 칼럼은 InsiderDetectionConcurrencyMariaDbTests가 실제 DB로 확인한다
class InsiderDetectionServiceTests {
    private static final Long STAFF = 4L;
    private static final Instant T0 = Instant.parse("2026-11-07T01:00:00Z");

    private final InsiderAlertRepository alerts = mock(InsiderAlertRepository.class);
    private final InsiderAlertEvidenceRepository evidence = mock(InsiderAlertEvidenceRepository.class);
    private final StaffAccessReader reader = mock(StaffAccessReader.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final StaffLoginReader loginReader = mock(StaffLoginReader.class);
    private final StaffCaseScopeReader scopeReader = mock(StaffCaseScopeReader.class);
    private final InsiderDetectionProperties properties = InsiderDetectionProperties.builder()
            .ins01Window(Duration.ofMinutes(10))
            .ins01CautionCases(3)
            .ins03Window(Duration.ofHours(1))
            .ins03CautionRawViews(3)
            .ins07AfterLogin(Duration.ofMinutes(30))
            .ins07Lookback(Duration.ofDays(30))
            .ins08Window(Duration.ofHours(1))
            .ins08CautionRows(1000)
            .ins08HighRows(3000)
            .ins08MaxLinkedRatio(0.3)
            .ins09Window(Duration.ofHours(1))
            .ins09CautionCustomers(10)
            .ins09HighCustomers(30)
            .scopeClosedLookback(Duration.ofDays(30))
            .build();
    private final FdsRulePolicyRepository rulePolicies = mock(FdsRulePolicyRepository.class);
    private final InsiderDetectionService service = new InsiderDetectionService(
            alerts,
            evidence,
            reader,
            publisher,
            properties,
            loginReader,
            scopeReader,
            mock(StaffAccountLocker.class),
            rulePolicies);
    // 규칙 켜짐 · 꺼짐 줄 (#685). 비어 있으면 모든 규칙이 켜진 것
    private final List<FdsRulePolicy> policyRows = new ArrayList<>();

    // 담당 사건: 가맹점 1의 고객 1:MINE 하나
    private static final StaffCaseScope SCOPE =
            new StaffCaseScope(Set.of(1L), Set.of(new StaffCaseScope.CaseCustomer(1L, "1:MINE")));

    private final List<InsiderAlert> savedAlerts = new ArrayList<>();
    private final List<InsiderAlertEvidence> savedEvidence = new ArrayList<>();
    private final List<StaffAccessRecorded> records = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(alerts.save(any())).thenAnswer(invocation -> {
            InsiderAlert alert = invocation.getArgument(0);
            ReflectionTestUtils.setField(alert, "id", 100L + savedAlerts.size());
            savedAlerts.add(alert);
            return alert;
        });
        when(alerts.findByStaffIdAndRuleCodeAndStatus(anyLong(), any(), eq(InsiderAlertStatus.OPEN)))
                .thenAnswer(invocation -> savedAlerts.stream()
                        .filter(a -> a.getStaffId().equals(invocation.getArgument(0))
                                && a.getRuleCode() == invocation.getArgument(1)
                                && a.isOpen())
                        .findFirst());
        when(alerts.findByStaffIdAndStatus(anyLong(), eq(InsiderAlertStatus.OPEN)))
                .thenAnswer(invocation -> savedAlerts.stream()
                        .filter(a -> a.getStaffId().equals(invocation.getArgument(0)) && a.isOpen())
                        .toList());
        when(alerts.findById(anyLong()))
                .thenAnswer(invocation -> savedAlerts.stream()
                        .filter(a -> a.getId().equals(invocation.getArgument(0)))
                        .findFirst());
        when(evidence.save(any())).thenAnswer(invocation -> {
            savedEvidence.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(evidence.findAccessEventIds(anyLong()))
                .thenAnswer(invocation -> savedEvidence.stream()
                        .filter(e -> e.getAlertId().equals(invocation.getArgument(0)))
                        .map(InsiderAlertEvidence::getAccessEventId)
                        .toList());
        when(evidence.findAccessEventIdsOf(any())).thenAnswer(invocation -> {
            Collection<Long> alertIds = invocation.getArgument(0);
            return savedEvidence.stream()
                    .filter(e -> alertIds.contains(e.getAlertId()))
                    .map(InsiderAlertEvidence::getAccessEventId)
                    .distinct()
                    .toList();
        });
        when(reader.findRecent(eq(STAFF), any(), any())).thenAnswer(invocation -> {
            Instant from = invocation.getArgument(1);
            Instant to = invocation.getArgument(2);
            // 실제 읽기 창구처럼 시각 · 기록 번호 순으로 준다 — 늦게 들어온 기록도 제자리에 놓인다
            return records.stream()
                    .filter(r ->
                            !r.occurredAt().isBefore(from) && !r.occurredAt().isAfter(to))
                    .sorted(Comparator.comparing(StaffAccessRecorded::occurredAt)
                            .thenComparing(StaffAccessRecorded::eventId))
                    .toList();
        });
        when(reader.findByIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return records.stream().filter(r -> ids.contains(r.eventId())).toList();
        });
        when(scopeReader.findScope(eq(STAFF), any())).thenReturn(SCOPE);
        when(rulePolicies.findByRuleCodeIn(any())).thenAnswer(invocation -> List.copyOf(policyRows));
    }

    // 관리자가 끈 규칙이 계속 경보를 열면, 그 경보가 겹침 높음 → 내보내기 차단 · 계정 정지로 이어진다 (#685)
    @Test
    void detect_whenInsiderRuleDisabled_doesNotOpenAlert() {
        policyRows.add(policy(InsiderRule.INS_03, false, T0.minusSeconds(60)));
        given(rawView(1L, 501L, T0), rawView(2L, 502L, T0.plusSeconds(60)), rawView(3L, 503L, T0.plusSeconds(120)));

        service.detect(records.get(2));

        assertThat(savedAlerts).isEmpty();
    }

    // 다시 켜자마자 끈 동안의 기록까지 세면 지난 행동으로 경보가 몰려 열린다 — 켠 뒤 기록만 센다 (#685 결정 3)
    @Test
    void detect_afterInsiderRuleReenabled_ignoresAccessesBeforeReenable() {
        Instant reenabledAt = T0.plusSeconds(120);
        policyRows.add(policy(InsiderRule.INS_03, true, reenabledAt));
        given(rawView(1L, 501L, T0), rawView(2L, 502L, T0.plusSeconds(60)));
        StaffAccessRecorded firstAfter = given(rawView(3L, 503L, T0.plusSeconds(180)));

        service.detect(firstAfter);
        assertThat(savedAlerts).isEmpty();

        given(rawView(4L, 504L, T0.plusSeconds(240)));
        service.detect(given(rawView(5L, 505L, T0.plusSeconds(300))));

        assertThat(savedAlerts)
                .singleElement()
                .satisfies(a -> assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_03));
        assertThat(savedEvidence)
                .extracting(InsiderAlertEvidence::getAccessEventId)
                .containsExactly(3L, 4L, 5L);
    }

    // 겹침 규칙을 꺼도 높음 경보가 열리면 내보내기 차단까지 간다 — 각 규칙의 주의 경보는 그대로 열린다 (#685)
    @Test
    void detect_whenOverlapRuleDisabled_doesNotOpenOverlapAlert() {
        policyRows.add(policy(InsiderRule.INS_06, false, T0.minusSeconds(60)));
        given(rawView(1L, 501L, T0), rawView(2L, 502L, T0.plusSeconds(60)), rawView(3L, 503L, T0.plusSeconds(120)));
        service.detect(records.get(2));
        StaffAccessRecorded lastDenial = given(
                denied(4L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(180)),
                denied(5L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(240)),
                denied(6L, 13L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(300)));

        service.detect(lastDenial);

        assertThat(savedAlerts)
                .extracting(InsiderAlert::getRuleCode)
                .containsExactlyInAnyOrder(InsiderRule.INS_03, InsiderRule.INS_01);
        verify(publisher, never()).publishEvent(any(InsiderAlertRaised.class));
    }

    // 관리자가 스위치로 바꾼 줄 — 마지막으로 바꾼 사람 · 시각이 남는다
    private static FdsRulePolicy policy(InsiderRule rule, boolean active, Instant changedAt) {
        FdsRulePolicy policy = BeanUtils.instantiateClass(FdsRulePolicy.class);
        ReflectionTestUtils.setField(policy, "ruleCode", rule.name());
        policy.update(active, 1L, changedAt);
        return policy;
    }

    // 탈취한 계정으로 처음 보는 브라우저에서 들어와 바로 원문을 봐도 높음 경보 · P3 알림(InsiderAlertRaised)이 없으면 놓친다
    @Test
    void ins07_newBrowserLoginThenRawViewWithin30Min_raisesHighAndPublishes() {
        loggedIn(T0, "Chrome · macOS");
        pastLogins(login(OTHER_LOGIN, T0.minus(Duration.ofDays(2)), "Edge · Windows"));
        StaffAccessRecorded view = given(rawView(1L, 501L, T0.plusSeconds(5 * 60)));

        service.detect(view);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_07);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.HIGH);
            assertThat(a.getSummary()).contains("Chrome · macOS", "5분");
        });
        ArgumentCaptor<InsiderAlertRaised> raised = ArgumentCaptor.forClass(InsiderAlertRaised.class);
        verify(publisher).publishEvent(raised.capture());
        assertThat(raised.getValue().rule()).isEqualTo(InsiderRule.INS_07);
        assertThat(raised.getValue().severity()).isEqualTo(InsiderAlertSeverity.HIGH);
        assertThat(raised.getValue().staffId()).isEqualTo(STAFF);
    }

    // 평소 쓰던 브라우저로 일하는 직원에게 높음 경보가 뜨면 정상 직원이 조사 대상이 된다
    @Test
    void ins07_knownBrowserLogin_doesNotRaise() {
        loggedIn(T0, "Chrome · macOS");
        pastLogins(login(OTHER_LOGIN, T0.minus(Duration.ofDays(2)), "Chrome · macOS"));

        service.detect(given(rawView(1L, 501L, T0.plusSeconds(5 * 60))));

        assertThat(savedAlerts).isEmpty();
        verify(publisher, never()).publishEvent(any());
    }

    // 비교할 지난 로그인이 없는(이번 로그인뿐인) 신규 직원은 첫 원문 열람마다 높음 경보가 뜨게 된다
    @Test
    void ins07_noLoginHistoryIn30Days_doesNotRaise() {
        loggedIn(T0, "Chrome · macOS");
        pastLogins(login(LOGIN, T0, "Chrome · macOS"));

        service.detect(given(rawView(1L, 501L, T0.plusSeconds(5 * 60))));

        assertThat(savedAlerts).isEmpty();
    }

    // 맡은 사건과 상관없는 거래 목록을 대량으로 훑어도 경보가 없으면 놓친다
    @Test
    void ins08_unlinkedRowsReachThresholdInHour_raisesCaution() {
        StaffAccessRecorded last =
                given(listView(1L, null, null, 600, T0), listView(2L, 2L, null, 500, T0.plusSeconds(10 * 60)));

        service.detect(last);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_08);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION);
            assertThat(a.getSummary()).contains("1100행", "담당 사건 연결 0%");
        });
    }

    // 담당 사건 가맹점의 문의로 그 가맹점 거래를 여러 쪽 넘긴 정상 업무가 경보가 되면 안 된다
    @Test
    void ins08_merchantInquiryOnAssignedCaseMerchant_doesNotRaise() {
        StaffAccessRecorded last = given(
                listView(1L, 1L, null, 400, T0),
                listView(2L, 1L, null, 400, T0.plusSeconds(60)),
                listView(3L, 1L, null, 400, T0.plusSeconds(120)),
                listView(4L, null, null, 200, T0.plusSeconds(180)));

        service.detect(last);

        assertThat(savedAlerts).isEmpty();
    }

    // 목록 대신 CSV 내보내기만 반복해 무관 거래를 가져가도 대량 조회 경보가 없으면 놓친다 (#651 Codex 리뷰)
    @Test
    void ins08_unlinkedRowsByExportsOnly_raisesCaution() {
        StaffAccessRecorded last =
                given(export(1L, null, null, 600, T0), export(2L, 2L, null, 500, T0.plusSeconds(600)));

        service.detect(last);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_08);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION);
            assertThat(a.getSummary()).contains("1100행");
        });
    }

    // 주의에 머문 채 1시간에 3,000행을 가져가도 높음이 되지 않으면 내보내기 차단(INS-05)이 끝내 걸리지 않는다 (#651)
    // 1,000행씩 세 번 — 두 번째까지 주의, 세 번째에 같은 경보가 높음으로 오르고 P3 알림(InsiderAlertRaised)이 나간다
    @Test
    void ins08_unlinkedRowsReachHighThreshold_raisesSameAlertToHighAndPublishes() {
        service.detect(given(export(1L, null, null, 1000, T0)));
        service.detect(given(export(2L, null, null, 1000, T0.plusSeconds(600))));
        assertThat(savedAlerts)
                .singleElement()
                .satisfies(a -> assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION));
        verify(publisher, never()).publishEvent(any());

        service.detect(given(export(3L, null, null, 1000, T0.plusSeconds(1200))));

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_08);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.HIGH);
            assertThat(a.getSummary()).contains("3000행");
        });
        ArgumentCaptor<InsiderAlertRaised> raised = ArgumentCaptor.forClass(InsiderAlertRaised.class);
        verify(publisher).publishEvent(raised.capture());
        assertThat(raised.getValue().rule()).isEqualTo(InsiderRule.INS_08);
        assertThat(raised.getValue().severity()).isEqualTo(InsiderAlertSeverity.HIGH);
    }

    // 담당 사건에 없는 고객을 여러 명 찾아봐도 경보가 없으면 특정인 조회를 놓친다
    @Test
    void ins09_unrelatedCustomersReachThreshold_raisesCaution() {
        for (int i = 0; i < 10; i++) given(listView(i + 1L, 1L, "1:C" + i, 3, T0.plusSeconds(i * 60L)));

        service.detect(records.getLast());

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_09);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION);
            assertThat(a.getSummary()).contains("10명").doesNotContain("1:C");
        });
    }

    // 고객 1명을 여러 쪽 넘긴 것을 여러 명으로 세면 정상 조회가 경보가 된다 (담당 고객 검색도 세지 않는다)
    @Test
    void ins09_sameUnrelatedCustomerPagedManyTimes_countsOnce() {
        for (int i = 0; i < 9; i++) given(listView(i + 1L, 1L, "1:C" + i, 3, T0.plusSeconds(i * 60L)));
        for (int i = 0; i < 5; i++) given(listView(20L + i, 1L, "1:C0", 3, T0.plusSeconds(600 + i * 60L)));
        given(listView(30L, 1L, "1:MINE", 3, T0.plusSeconds(1200)));

        service.detect(records.getLast());

        assertThat(savedAlerts).isEmpty();
    }

    // 훑기 하나가 INS-08 · 09로 함께 잡혀 겹침 높음이 되면 내부자 사건(조각 8)으로 넘어가 정상보다 무겁게 다뤄진다
    @Test
    void ins06_ins08AndIns09Only_doesNotRaiseHigh() {
        for (int i = 0; i < 10; i++) given(listView(i + 1L, 1L, "1:C" + i, 110, T0.plusSeconds(i * 60L)));

        service.detect(records.getLast());

        assertThat(savedAlerts)
                .extracting(InsiderAlert::getRuleCode)
                .containsExactlyInAnyOrder(InsiderRule.INS_08, InsiderRule.INS_09);
        verify(publisher, never()).publishEvent(any());
    }

    // 10분 안에 서로 다른 사건 3개에서 막혔는데 경보가 없으면 남의 사건을 두드리는 직원을 놓친다 (사건 밖 결제 시도도 센다)
    @Test
    void ins01_deniedThreeDifferentCasesIn10Min_raisesCaution() {
        StaffAccessRecorded last = given(
                denied(1L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0),
                denied(2L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(120)),
                denied(3L, 13L, StaffAccessDenyReason.PAYMENT_NOT_IN_CASE, T0.plusSeconds(240)));

        service.detect(last);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_01);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION);
        });
        assertThat(savedEvidence)
                .extracting(InsiderAlertEvidence::getAccessEventId)
                .containsExactly(1L, 2L, 3L);
        verify(publisher, never()).publishEvent(any(InsiderAlertRaised.class));
    }

    // 같은 사건을 새로고침하다 3번 막힌 것까지 경보로 올리면 오탐이 쌓인다
    @Test
    void ins01_sameCaseDeniedThreeTimes_doesNotRaise() {
        StaffAccessRecorded last = given(
                denied(1L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0),
                denied(2L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(30)),
                denied(3L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(60)));

        service.detect(last);

        assertThat(savedAlerts).isEmpty();
    }

    // 닫힌 사건 거부는 지난 사건 확인처럼 정상 업무에서도 나온다 — 세면 정상 직원에게 경보가 뜬다 (11/07 결정)
    @Test
    void ins01_caseClosedDenials_areNotCounted() {
        given(
                denied(1L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0),
                denied(2L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(60)));
        service.detect(records.get(1));
        StaffAccessRecorded closedCase = given(denied(3L, 13L, StaffAccessDenyReason.CASE_CLOSED, T0.plusSeconds(120)));

        service.detect(closedCase);

        assertThat(savedAlerts).isEmpty();
    }

    // 담당 밖 고객 3명의 거래를 10분 안에 내보내려다 막혔는데 경보가 없으면 반출 시도를 놓친다 (#730)
    // 내보내기 거부에는 사건 번호가 없어 고객 단위로 센다
    @Test
    void ins01_outOfScopeExportDenialsForDistinctCustomers_raisesCaution() {
        StaffAccessRecorded last = given(
                exportDenied(1L, 7L, "7:1001", T0),
                exportDenied(2L, 7L, "7:1002", T0.plusSeconds(120)),
                exportDenied(3L, 7L, "7:1003", T0.plusSeconds(240)));

        service.detect(last);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_01);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION);
        });
        assertThat(savedEvidence)
                .extracting(InsiderAlertEvidence::getAccessEventId)
                .containsExactly(1L, 2L, 3L);
    }

    // 주소를 바꿔 가며 남의 사건 고객 결제 상세를 두드려도 경보가 없으면 막힌 시도가 묻힌다 (#732)
    @Test
    void ins01_outOfScopeDetailDenialsForDistinctCustomers_raisesCaution() {
        StaffAccessRecorded last = given(
                detailDenied(1L, 7L, "7:1001", 501L, T0),
                detailDenied(2L, 7L, "7:1002", 502L, T0.plusSeconds(120)),
                detailDenied(3L, 7L, "7:1003", 503L, T0.plusSeconds(240)));

        service.detect(last);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_01);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION);
        });
    }

    // 1시간 안 원문 열람이 기준에 닿았는데 경보가 없으면 몰아보기를 놓친다 (기준 전에는 경보 없음)
    @Test
    void ins03_rawViewsReachThresholdInHour_raisesCaution() {
        given(rawView(1L, 501L, T0), rawView(2L, 502L, T0.plusSeconds(600)));
        service.detect(records.get(1));
        assertThat(savedAlerts).isEmpty();

        StaffAccessRecorded third = given(rawView(3L, 503L, T0.plusSeconds(1200)));
        service.detect(third);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_03);
            assertThat(a.getSeverity()).isEqualTo(InsiderAlertSeverity.CAUTION);
        });
    }

    // 두 규칙의 주의가 겹쳤는데 알림이 없으면 내부자 사건(조각 8)이 만들어지지 않는다
    @Test
    void ins06_twoCautionAlertsFromDifferentRules_raisesHighAndPublishesInsiderAlertRaised() {
        given(rawView(1L, 501L, T0), rawView(2L, 502L, T0.plusSeconds(60)), rawView(3L, 503L, T0.plusSeconds(120)));
        service.detect(records.get(2));
        StaffAccessRecorded lastDenial = given(
                denied(4L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(180)),
                denied(5L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(240)),
                denied(6L, 13L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(300)));

        service.detect(lastDenial);

        InsiderAlert ins06 = savedAlerts.stream()
                .filter(a -> a.getRuleCode() == InsiderRule.INS_06)
                .findFirst()
                .orElseThrow();
        assertThat(ins06.getSeverity()).isEqualTo(InsiderAlertSeverity.HIGH);
        ArgumentCaptor<InsiderAlertRaised> raised = ArgumentCaptor.forClass(InsiderAlertRaised.class);
        verify(publisher).publishEvent(raised.capture());
        assertThat(raised.getValue().alertId()).isEqualTo(ins06.getId());
        assertThat(raised.getValue().staffId()).isEqualTo(STAFF);
        assertThat(raised.getValue().rule()).isEqualTo(InsiderRule.INS_06);
        assertThat(raised.getValue().severity()).isEqualTo(InsiderAlertSeverity.HIGH);
    }

    // 같은 기록이 다시 전달될 때 근거가 두 번 붙으면 건수가 부풀어 관리자 판단이 틀어진다
    @Test
    void sameEventTwice_addsEvidenceOnce() {
        StaffAccessRecorded last = given(
                denied(1L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0),
                denied(2L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(60)),
                denied(3L, 13L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(120)));

        service.detect(last);
        service.detect(last);

        assertThat(savedAlerts).hasSize(1);
        assertThat(savedEvidence)
                .extracting(InsiderAlertEvidence::getAccessEventId)
                .containsExactly(1L, 2L, 3L);
    }

    // 같은 직원 · 규칙 경보가 새 줄로 쌓이면 관리자 화면이 도배되고 사건도 여러 개 생긴다
    @Test
    void openAlert_sameStaffSameRule_updatesExistingNotDuplicate() {
        given(
                denied(1L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0),
                denied(2L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(60)),
                denied(3L, 13L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(120)));
        service.detect(records.get(2));
        Instant later = T0.plusSeconds(1800);
        StaffAccessRecorded last = given(
                denied(4L, 14L, StaffAccessDenyReason.NOT_ASSIGNEE, later),
                denied(5L, 15L, StaffAccessDenyReason.NOT_ASSIGNEE, later.plusSeconds(60)),
                denied(6L, 16L, StaffAccessDenyReason.NOT_ASSIGNEE, later.plusSeconds(120)));

        service.detect(last);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_01);
            assertThat(a.getLastEventAt()).isEqualTo(last.occurredAt());
        });
        assertThat(savedEvidence)
                .extracting(InsiderAlertEvidence::getAccessEventId)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    // 관리자가 정상 업무로 닫은 뒤 같은 근거로 바로 다시 열리면 닫은 판단이 뒤집힌다
    @Test
    void closedAlert_eventsBeforeClose_doNotReopen() {
        StaffAccessRecorded third = given(
                denied(1L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0),
                denied(2L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(60)),
                denied(3L, 13L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(120)));
        service.detect(third);
        Instant closedAt = T0.plusSeconds(300);
        savedAlerts.getFirst().close(1L, "관리자", "지난 이의제기 확인", closedAt);
        when(alerts.findLastClosedAt(STAFF, InsiderRule.INS_01)).thenReturn(closedAt);

        StaffAccessRecorded fourth = given(denied(4L, 14L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(360)));
        service.detect(fourth);

        assertThat(savedAlerts)
                .singleElement()
                .satisfies(a -> assertThat(a.isOpen()).isFalse());
    }

    // (#627 리뷰) 관리자가 겹침 경보만 닫았는데 같은 두 경보로 다시 열리면 닫은 판단이 뒤집히고 내부자 사건 알림이 또 나간다
    @Test
    void ins06_closedWhileSameAlertsStayOpen_doesNotReopen() {
        given(rawView(1L, 501L, T0), rawView(2L, 502L, T0.plusSeconds(60)), rawView(3L, 503L, T0.plusSeconds(120)));
        service.detect(records.get(2));
        StaffAccessRecorded lastDenial = given(
                denied(4L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(180)),
                denied(5L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(240)),
                denied(6L, 13L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(300)));
        service.detect(lastDenial);
        InsiderAlert ins06 = savedAlerts.stream()
                .filter(a -> a.getRuleCode() == InsiderRule.INS_06)
                .findFirst()
                .orElseThrow();
        Instant closedAt = T0.plusSeconds(600);
        ins06.close(1L, "관리자", "같은 이의제기 묶음", closedAt);
        when(alerts.findLastClosedAt(STAFF, InsiderRule.INS_06)).thenReturn(closedAt);

        service.detect(lastDenial);

        assertThat(savedAlerts)
                .filteredOn(a -> a.getRuleCode() == InsiderRule.INS_06)
                .singleElement();
        verify(publisher).publishEvent(any(InsiderAlertRaised.class));
    }

    // (#627 리뷰) 먼저 일어난 열람이 늦게 커밋돼 마지막에 처리되면, 그 시각 뒤 기록까지 세지 않아 몰아보기를 놓친다
    @Test
    void ins03_earlierViewCommittedLast_stillRaisesCaution() {
        StaffAccessRecorded first = rawView(1L, 501L, T0);
        given(rawView(2L, 502L, T0.plusSeconds(600)), rawView(3L, 503L, T0.plusSeconds(1200)));
        service.detect(records.get(1));
        assertThat(savedAlerts).isEmpty();

        given(first);
        service.detect(first);

        assertThat(savedAlerts).singleElement().satisfies(a -> {
            assertThat(a.getRuleCode()).isEqualTo(InsiderRule.INS_03);
            assertThat(a.getLastEventAt()).isEqualTo(T0.plusSeconds(1200));
        });
        assertThat(savedEvidence)
                .extracting(InsiderAlertEvidence::getAccessEventId)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // 관리자의 검토 업무가 내부자 경보가 되면 관리자 화면이 자기 경보로 채워진다
    @Test
    void adminActor_doesNotRaise() {
        StaffAccessRecorded last = given(
                admin(denied(1L, 11L, StaffAccessDenyReason.NOT_ASSIGNEE, T0)),
                admin(denied(2L, 12L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(60))),
                admin(denied(3L, 13L, StaffAccessDenyReason.NOT_ASSIGNEE, T0.plusSeconds(120))));

        service.detect(last);

        assertThat(savedAlerts).isEmpty();
    }

    // 경보 요약 · 근거 화면으로 원문 · IP · 고객 번호 · 로그인 번호가 새면 관리자 화면이 유출 통로가 된다 (ADR-0082 1절)
    @Test
    void alertSummaryAndEvidence_containNoRawContactOrIp() {
        given(rawView(1L, 501L, T0), rawView(2L, 502L, T0.plusSeconds(60)), rawView(3L, 503L, T0.plusSeconds(120)));
        service.detect(records.get(2));
        InsiderAlert alert = savedAlerts.getFirst();
        CurrentStaff currentStaff = mock(CurrentStaff.class);
        when(currentStaff.findCurrent())
                .thenReturn(Optional.of(StaffSummary.builder()
                        .userId(1L)
                        .displayName("관리자")
                        .role("ADMIN")
                        .build()));
        SecurityStaffDirectory staffDirectory = mock(SecurityStaffDirectory.class);
        when(staffDirectory.securityStaff()).thenReturn(List.of(new SecurityStaffEntry(STAFF, "보안 직원", true)));
        MerchantDirectoryReader merchants = mock(MerchantDirectoryReader.class);
        when(merchants.findAll()).thenReturn(List.of(new MerchantDirectoryEntry(1L, "M001", "케이크 가게", "ACTIVE")));
        InsiderAlertService alertService =
                new InsiderAlertService(alerts, evidence, reader, currentStaff, staffDirectory, merchants);

        InsiderAlertDetailResponse detail = alertService.detail(alert.getId());
        String json = JsonMapper.builder().build().writeValueAsString(detail);

        assertThat(detail.evidence()).hasSize(3);
        assertThat(alert.getSummary()).doesNotContain("CUST-7788");
        assertThat(json).doesNotContain("CUST-7788", LOGIN.toString());
        // 값으로 나오는 기록 종류 코드(ORDERER_PHONE)는 괜찮다. 칸 이름으로 원문 · IP 류가 생기면 안 된다
        assertThat(fieldNames(json))
                .isNotEmpty()
                .noneMatch(name -> name.toLowerCase().matches(".*(phone|customer|login|orderer|address).*|ip|.*ip$"));
    }

    private static List<String> fieldNames(String json) {
        return Pattern.compile("\"(\\w+)\"\\s*:")
                .matcher(json)
                .results()
                .map(m -> m.group(1))
                .toList();
    }

    private static final UUID LOGIN = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_LOGIN = UUID.fromString("66666666-7777-8888-9999-000000000000");

    // 원문 열람 기록의 세션(LOGIN)이 이 시각 · 브라우저로 로그인했다
    private void loggedIn(Instant at, String device) {
        when(loginReader.findLogin(LOGIN)).thenReturn(Optional.of(login(LOGIN, at, device)));
    }

    private void pastLogins(StaffLogin... logins) {
        when(loginReader.findSuccessfulLogins(eq(STAFF), any(), any())).thenReturn(List.of(logins));
    }

    private static StaffLogin login(UUID reference, Instant at, String device) {
        return StaffLogin.builder()
                .staffId(STAFF)
                .loginReference(reference)
                .occurredAt(at)
                .device(device)
                .build();
    }

    // 거래 목록 조회 기록 — 거른 가맹점 · 검색한 고객 번호 · 내보낸 행 수 (#615)
    private static StaffAccessRecorded listView(
            Long eventId, Long merchantId, String customerRef, int rows, Instant at) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.TRANSACTION_LIST_VIEW)
                .result(StaffAccessResult.ALLOWED)
                .merchantId(merchantId)
                .customerRef(customerRef)
                .itemCount(rows)
                .loginReference(LOGIN)
                .occurredAt(at)
                .build();
    }

    // 허용된 거래 내역 CSV 내보내기 기록 — 목록 조회와 같은 칸(거른 가맹점 · 고객 번호 · 행 수) (#651)
    private static StaffAccessRecorded export(Long eventId, Long merchantId, String customerRef, int rows, Instant at) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.TRANSACTION_EXPORT)
                .result(StaffAccessResult.ALLOWED)
                .merchantId(merchantId)
                .customerRef(customerRef)
                .itemCount(rows)
                .loginReference(LOGIN)
                .occurredAt(at)
                .build();
    }

    // 담당 밖 고객의 거래 상세 거부 기록 (#732) — 결제 번호 · 가맹점 · 고객 번호, 사건 번호 없음
    private static StaffAccessRecorded detailDenied(
            Long eventId, Long merchantId, String customerRef, Long paymentId, Instant at) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.TRANSACTION_DETAIL_VIEW)
                .result(StaffAccessResult.DENIED)
                .denyReason(StaffAccessDenyReason.TRANSACTION_DETAIL_OUT_OF_SCOPE)
                .merchantId(merchantId)
                .paymentId(paymentId)
                .customerRef(customerRef)
                .occurredAt(at)
                .build();
    }

    private StaffAccessRecorded given(StaffAccessRecorded... added) {
        records.addAll(List.of(added));
        return added[added.length - 1];
    }

    private static StaffAccessRecorded denied(Long eventId, Long caseId, StaffAccessDenyReason reason, Instant at) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.CASE_DETAIL_VIEW)
                .result(StaffAccessResult.DENIED)
                .denyReason(reason)
                .caseId(caseId)
                .occurredAt(at)
                .build();
    }

    private static StaffAccessRecorded exportDenied(Long eventId, Long merchantId, String customerRef, Instant at) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.TRANSACTION_EXPORT)
                .result(StaffAccessResult.DENIED)
                .denyReason(StaffAccessDenyReason.EXPORT_OUT_OF_SCOPE)
                .merchantId(merchantId)
                .customerRef(customerRef)
                .occurredAt(at)
                .build();
    }

    // 원문 열람 기록에는 고객 번호 · 로그인 번호가 붙어 온다 — 근거 화면으로 옮겨지지 않는지 보려고 채운다
    private static StaffAccessRecorded rawView(Long eventId, Long paymentId, Instant at) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.CONTACT_RAW_VIEW)
                .result(StaffAccessResult.ALLOWED)
                .caseId(900L)
                .merchantId(1L)
                .paymentId(paymentId)
                .customerRef("CUST-7788")
                .fieldType(StaffAccessFieldType.ORDERER_PHONE)
                .loginReference(LOGIN)
                .occurredAt(at)
                .build();
    }

    private static StaffAccessRecorded admin(StaffAccessRecorded record) {
        return StaffAccessRecorded.builder()
                .eventId(record.eventId())
                .staffId(record.staffId())
                .actorRole("ADMIN")
                .accessType(record.accessType())
                .result(record.result())
                .denyReason(record.denyReason())
                .caseId(record.caseId())
                .occurredAt(record.occurredAt())
                .build();
    }
}
