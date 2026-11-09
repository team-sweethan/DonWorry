package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertEvidenceReader;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessResult;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.entity.InsiderCaseStatus;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.entity.InsiderReport;
import com.donworry.investigation.report.entity.InsiderReportTrigger;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.InsiderReportRepository;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InsiderCaseChanged;
import com.donworry.investigation.service.SecurityStaffGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

// 내부자 사건 해제 검토 보고서 (#675) — 사실 집계 · 판 누적 · 실패 시 판 없이 예외(#702). 저장소는 메모리 목록으로 대신한다
// P2 근거 · P1 접근 기록 · 정지 여부는 공개 창구 대역으로 정한다
@ExtendWith(MockitoExtension.class)
class InsiderReportServiceTests {

    private static final Long CASE_ID = 10L;
    private static final Long STAFF_ID = 21L;
    private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
    private static final String CUSTOMER_A = "CUST-a1b2c3d4";
    private static final String CUSTOMER_B = "CUST-e5f6a7b8";
    private static final String CUSTOMER_C = "CUST-c9d0e1f2";
    // 평소 기록 시각 — T0(한국 11/08 10:00) 하루 전
    private static final Instant DAY_BEFORE = T0.minus(Duration.ofDays(1));
    private static final UUID LOGIN_REF = UUID.fromString("0f5c1d2e-3b4a-4c5d-8e9f-a0b1c2d3e4f5");

    @Mock
    InsiderCaseRepository insiderCases;

    @Mock
    InsiderCaseAlertRepository links;

    @Mock
    SecurityCaseRepository securityCases;

    @Mock
    InsiderReportRepository reports;

    @Mock
    InsiderAlertEvidenceReader evidenceReader;

    @Mock
    StaffAccessReader accessReader;

    @Mock
    SecurityStaffDirectory staffDirectory;

    @Mock
    StaffAccountLocker locker;

    @Mock
    SecurityStaffGuard guard;

    // 저장된 판 — 저장소 대역이 이 목록을 읽고 쓴다
    private final List<InsiderReport> saved = new ArrayList<>();

    private InsiderReportService service;

    @BeforeEach
    void setUp() {
        service = new InsiderReportService(
                insiderCases,
                links,
                securityCases,
                reports,
                new InsiderReportWriter(reports),
                evidenceReader,
                accessReader,
                staffDirectory,
                locker,
                guard);
        lenient()
                .when(reports.existsByInsiderCaseIdAndTriggerType(anyLong(), any()))
                .thenAnswer(call -> saved.stream()
                        .anyMatch(r -> r.getInsiderCaseId().equals(call.getArgument(0))
                                && r.getTriggerType() == call.getArgument(1)));
        lenient()
                .when(reports.findMaxReportVersion(anyLong()))
                .thenAnswer(call -> saved.stream()
                        .filter(r -> r.getInsiderCaseId().equals(call.getArgument(0)))
                        .map(InsiderReport::getReportVersion)
                        .max(Integer::compare));
        lenient().when(reports.saveAndFlush(any(InsiderReport.class))).thenAnswer(call -> store(call.getArgument(0)));
        lenient().when(insiderCases.findById(CASE_ID)).thenReturn(Optional.of(insiderCase()));
        lenient()
                .when(staffDirectory.securityStaff())
                .thenReturn(List.of(new SecurityStaffEntry(STAFF_ID, "김보안", true)));
    }

    // 보고서 본문에 고객 번호 · 로그인 번호(세션) 값이 들어가지 않는다 — 고객은 "몇 명"으로만 남는다
    @Test
    void facts_containNoCustomerRefIpOrRawValue() {
        givenAlerts(alert(500L, InsiderRule.INS_09));
        givenAccess(
                access(1L, StaffAccessType.TRANSACTION_LIST_VIEW, StaffAccessResult.ALLOWED, CUSTOMER_A, 100L, 30),
                access(2L, StaffAccessType.TRANSACTION_LIST_VIEW, StaffAccessResult.ALLOWED, CUSTOMER_B, 100L, 20));
        // 평소(30일) 기록의 고객 번호도 본문에 들어가지 않는다 (#749)
        when(accessReader.findRecent(eq(STAFF_ID), any(), any()))
                .thenReturn(List.of(usual(90L, StaffAccessType.TRANSACTION_LIST_VIEW, CUSTOMER_C, DAY_BEFORE)));

        service.generate(opened(500L));

        assertThat(saved).hasSize(1);
        String body = String.valueOf(saved.getFirst().getReportBody());
        assertThat(body).doesNotContain(CUSTOMER_A, CUSTOMER_B, CUSTOMER_C, LOGIN_REF.toString());
        assertThat(body).doesNotContain("customerRef", "loginReference", "ip=");
        assertThat(asMap(facts(saved.getFirst()).get("access"))).containsEntry("distinctCustomers", 2L);
    }

    // 사건 생성 뒤 차단 경보가 붙으면 1판은 그대로 두고 2판을 더 쌓는다
    @Test
    void caseOpenedThenBlocked_accumulatesVersion1And2() {
        givenAlerts(alert(500L, InsiderRule.INS_08));
        givenAccess();
        service.generate(opened(500L));

        givenAlerts(alert(500L, InsiderRule.INS_08), alert(501L, InsiderRule.INS_05));
        when(locker.isLocked(STAFF_ID)).thenReturn(true);
        service.generate(new InsiderCaseChanged(CASE_ID, InsiderCaseChanged.Reason.EXPORT_BLOCKED, 501L));

        assertThat(saved)
                .extracting(InsiderReport::getReportVersion, InsiderReport::getTriggerType, InsiderReport::getStatus)
                .containsExactly(
                        tuple(1, InsiderReportTrigger.CASE_OPENED, ReportStatus.SUCCEEDED),
                        tuple(2, InsiderReportTrigger.EXPORT_BLOCKED, ReportStatus.SUCCEEDED));
        assertThat(facts(saved.getFirst()).get("accountLocked")).isEqualTo(false);
        assertThat(facts(saved.get(1)).get("accountLocked")).isEqualTo(true);
        assertThat(saved.get(1).getTriggerAlertId()).isEqualTo(501L);
    }

    // 같은 알림이 다시 와도(재전달) 판은 하나다
    @Test
    void sameTriggerTwice_createsOneVersion() {
        givenAlerts(alert(500L, InsiderRule.INS_01));
        givenAccess();

        service.generate(opened(500L));
        service.generate(opened(500L));

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getReportVersion()).isEqualTo(1);
    }

    // 사실 읽기가 실패하면 판을 남기지 않고 예외로 끝낸다 (#702) — 실패 판이 계기를 막으면 다시 보내도 영영 만들지 못한다.
    // 그 뒤 같은 알림이 다시 오면(재전달) 정상 판이 만들어진다
    @Test
    void generate_factsReadFails_throwsWithoutSavingReport() {
        givenAlerts(alert(500L, InsiderRule.INS_01));
        when(evidenceReader.accessEventIds(anyCollection()))
                .thenThrow(new IllegalStateException("fds 읽기 실패"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(opened(500L))).isInstanceOf(IllegalStateException.class);
        assertThat(saved).isEmpty();

        service.generate(opened(500L));

        assertThat(saved).singleElement().satisfies(report -> {
            assertThat(report.getStatus()).isEqualTo(ReportStatus.SUCCEEDED);
            assertThat(report.getReportVersion()).isEqualTo(1);
        });
    }

    // 접근 기록을 종류별 허용 · 거부 · 행 수로 나누고, 서로 다른 고객 · 가맹점 · 사건 수와 기간을 센다
    @Test
    void facts_countsAccessByType_andDistinctCustomers() {
        givenAlerts(alert(500L, InsiderRule.INS_08), alert(501L, InsiderRule.INS_01));
        givenAccess(
                access(1L, StaffAccessType.TRANSACTION_LIST_VIEW, StaffAccessResult.ALLOWED, CUSTOMER_A, 100L, 30),
                access(2L, StaffAccessType.TRANSACTION_LIST_VIEW, StaffAccessResult.ALLOWED, CUSTOMER_A, 200L, 20),
                access(3L, StaffAccessType.TRANSACTION_EXPORT, StaffAccessResult.ALLOWED, null, 100L, 50),
                caseAccess(4L, StaffAccessResult.DENIED, 7L),
                caseAccess(5L, StaffAccessResult.ALLOWED, 8L),
                caseAccess(6L, StaffAccessResult.DENIED, 7L));

        service.generate(opened(500L));

        Map<String, Object> access = asMap(facts(saved.getFirst()).get("access"));
        assertThat(access)
                .containsEntry("total", 6)
                .containsEntry("distinctCustomers", 1L)
                .containsEntry("distinctMerchants", 2L)
                .containsEntry("distinctCases", 2L)
                .containsEntry("periodFrom", T0.plusSeconds(1).toString())
                .containsEntry("periodTo", T0.plusSeconds(6).toString());
        assertThat(asList(access.get("byType")))
                .extracting(
                        row -> row.get("type"),
                        row -> row.get("allowed"),
                        row -> row.get("denied"),
                        row -> row.get("items"))
                .containsExactly(
                        tuple("CASE_DETAIL_VIEW", 1L, 2L, 0L),
                        tuple("TRANSACTION_LIST_VIEW", 2L, 0L, 50L),
                        tuple("TRANSACTION_EXPORT", 1L, 0L, 50L));
        assertThat(asList(facts(saved.getFirst()).get("alerts")))
                .extracting(row -> row.get("ruleCode"))
                .containsExactly("SEC-INS-08", "SEC-INS-01");
    }

    // 고객 번호가 비어 있는 원문 열람 기록도 사건 고객으로 세고, 고객을 못 찾은 기록은 0명으로 숨기지 않고 따로 센다 (#675 Codex 리뷰)
    @Test
    void facts_rawViewWithoutCustomerRef_countsCustomerFromCase() {
        givenAlerts(alert(500L, InsiderRule.INS_07));
        givenAccess(rawView(1L, 7L), rawView(2L, 8L), rawView(3L, 7L), rawView(4L, 9L));
        when(securityCases.findById(7L)).thenReturn(Optional.of(securityCase(CUSTOMER_A)));
        when(securityCases.findById(8L)).thenReturn(Optional.of(securityCase(CUSTOMER_B)));
        when(securityCases.findById(9L)).thenReturn(Optional.empty());

        service.generate(opened(500L));

        Map<String, Object> access = asMap(facts(saved.getFirst()).get("access"));
        assertThat(access).containsEntry("distinctCustomers", 2L).containsEntry("unresolvedCustomerRecords", 1L);
        assertThat(String.valueOf(saved.getFirst().getReportBody())).doesNotContain(CUSTOMER_A, CUSTOMER_B);
    }

    // 평소(30일)는 근거 첫 기록 직전 30일이다 (#749) — 근거 기록 자체와 근거 첫 시각의 기록은 평소에 섞지 않는다
    @Test
    void facts_baselineUsesThirtyDaysBeforeFirstEvidence_excludesEvidenceEvents() {
        givenAlerts(alert(500L, InsiderRule.INS_09));
        StaffAccessRecorded evidence =
                access(1L, StaffAccessType.TRANSACTION_LIST_VIEW, StaffAccessResult.ALLOWED, CUSTOMER_A, 100L, 3);
        givenAccess(evidence);
        Instant firstEvidence = evidence.occurredAt();
        // 읽기 창구는 끝 시각을 포함해 돌려준다 — 근거 기록과 같은 시각의 다른 기록도 함께 온다
        when(accessReader.findRecent(STAFF_ID, firstEvidence.minus(Duration.ofDays(30)), firstEvidence))
                .thenReturn(List.of(
                        usual(90L, StaffAccessType.TRANSACTION_LIST_VIEW, CUSTOMER_B, DAY_BEFORE),
                        evidence,
                        usual(91L, StaffAccessType.TRANSACTION_LIST_VIEW, CUSTOMER_C, firstEvidence)));

        service.generate(opened(500L));

        Map<String, Object> baseline = asMap(facts(saved.getFirst()).get("baseline"));
        assertThat(baseline)
                .containsEntry("from", firstEvidence.minus(Duration.ofDays(30)).toString())
                .containsEntry("to", firstEvidence.toString())
                .containsEntry("hasRecords", true)
                .containsEntry("daysWithRecords", 1);
        assertThat(asList(baseline.get("rows")))
                .extracting(row -> row.get("key"), row -> row.get("maxDay"), row -> row.get("current"))
                .containsExactly(
                        tuple("LIST_VIEWS", 1L, 1L),
                        tuple("SEARCHED_CUSTOMERS", 1L, 1L),
                        tuple("DENIED", 0L, 0L),
                        tuple("EXPORT_ATTEMPTS", 0L, 0L));
    }

    // 평소 기록이 없는 직원(새 계정 등)은 평균 · 최대를 0이 아니라 빈 값으로 둔다 (#749) — "평소 0건"으로 대비가 과장되지 않게
    @Test
    void facts_noAccessInBaselinePeriod_marksBaselineEmptyNotZero() {
        givenAlerts(alert(500L, InsiderRule.INS_09));
        givenAccess(access(1L, StaffAccessType.TRANSACTION_LIST_VIEW, StaffAccessResult.ALLOWED, CUSTOMER_A, 100L, 3));
        when(accessReader.findRecent(eq(STAFF_ID), any(), any())).thenReturn(List.of());

        service.generate(opened(500L));

        Map<String, Object> baseline = asMap(facts(saved.getFirst()).get("baseline"));
        assertThat(baseline).containsEntry("hasRecords", false).containsEntry("daysWithRecords", 0);
        assertThat(asList(baseline.get("rows")))
                .allSatisfy(row ->
                        assertThat(row).containsEntry("dailyAverage", null).containsEntry("maxDay", null));
        assertThat(asList(baseline.get("rows")).getFirst()).containsEntry("current", 1L);
    }

    // 평소 값은 한국 날짜로 하루를 나눠, 날마다 서로 다른 고객을 세고 30일로 나눈다 (#749)
    // 한국 자정 앞뒤 기록은 다른 날이다(UTC로는 같은 날). 같은 날 같은 고객 여러 쪽은 1명
    @Test
    void facts_baselineCountsDistinctCustomersPerDay_averageOverThirtyDays() {
        givenAlerts(alert(500L, InsiderRule.INS_09));
        givenAccess(access(1L, StaffAccessType.TRANSACTION_LIST_VIEW, StaffAccessResult.ALLOWED, CUSTOMER_A, 100L, 3));
        Instant beforeMidnight = Instant.parse("2026-11-06T14:30:00Z"); // 한국 11/06 23:30
        Instant afterMidnight = Instant.parse("2026-11-06T15:30:00Z"); // 한국 11/07 00:30
        when(accessReader.findRecent(eq(STAFF_ID), any(), any()))
                .thenReturn(List.of(
                        usual(90L, StaffAccessType.TRANSACTION_LIST_VIEW, CUSTOMER_A, beforeMidnight),
                        usual(91L, StaffAccessType.TRANSACTION_LIST_VIEW, CUSTOMER_A, beforeMidnight.plusSeconds(60)),
                        usual(92L, StaffAccessType.TRANSACTION_LIST_VIEW, CUSTOMER_A, beforeMidnight.plusSeconds(120)),
                        usual(93L, StaffAccessType.TRANSACTION_EXPORT, CUSTOMER_B, beforeMidnight.plusSeconds(180)),
                        usual(94L, StaffAccessType.TRANSACTION_LIST_VIEW, CUSTOMER_A, afterMidnight)));

        service.generate(opened(500L));

        Map<String, Object> baseline = asMap(facts(saved.getFirst()).get("baseline"));
        assertThat(baseline).containsEntry("daysWithRecords", 2);
        Map<String, Object> customers = asList(baseline.get("rows")).get(1);
        assertThat(customers)
                .containsEntry("key", "SEARCHED_CUSTOMERS")
                .containsEntry("maxDay", 2L)
                .containsEntry("dailyAverage", new BigDecimal("0.1"));
        Map<String, Object> listViews = asList(baseline.get("rows")).getFirst();
        assertThat(listViews).containsEntry("maxDay", 3L).containsEntry("dailyAverage", new BigDecimal("0.1"));
    }

    // 보고서 기능 배포 전부터 열린 사건은 서버 시작 때 빠진 판을 채우고, 다시 시작해도 판이 늘지 않는다 (#675 Codex 리뷰)
    @Test
    void backfill_openCaseWithoutReports_createsMissingVersionsOnce() {
        when(insiderCases.findOpenStaffIds()).thenReturn(Set.of(STAFF_ID));
        when(insiderCases.findByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN))
                .thenReturn(Optional.of(insiderCase()));
        givenAlerts(alert(500L, InsiderRule.INS_08), alert(501L, InsiderRule.INS_05));
        givenAccess();

        int first = service.backfillOpenCases();
        int second = service.backfillOpenCases();

        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();
        assertThat(saved)
                .extracting(
                        InsiderReport::getReportVersion,
                        InsiderReport::getTriggerType,
                        InsiderReport::getTriggerAlertId)
                .containsExactly(
                        tuple(1, InsiderReportTrigger.CASE_OPENED, 500L),
                        tuple(2, InsiderReportTrigger.EXPORT_BLOCKED, 501L));
    }

    // 열린 사건이 화면 목록 상한(100개)보다 많아도 서버 시작 채우기는 모든 열린 사건에 판을 만든다 (#675 Codex 2차 리뷰)
    @Test
    void backfill_moreThan100OpenCases_fillsAll() {
        Set<Long> staffIds = LongStream.rangeClosed(1, 101).boxed().collect(Collectors.toSet());
        when(insiderCases.findOpenStaffIds()).thenReturn(staffIds);
        when(insiderCases.findByStaffIdAndStatus(anyLong(), eq(InsiderCaseStatus.OPEN)))
                .thenAnswer(call -> Optional.of(caseOf(call.getArgument(0))));
        when(insiderCases.findById(anyLong()))
                .thenAnswer(call -> Optional.of(caseOf((Long) call.getArgument(0) - 1000)));
        when(links.findByInsiderCaseIdOrderByRaisedAtAscAlertIdAsc(anyLong()))
                .thenAnswer(call -> List.of(alertOf(call.getArgument(0), InsiderRule.INS_01)));
        when(evidenceReader.accessEventIds(anyCollection())).thenReturn(List.of());

        int requested = service.backfillOpenCases();

        assertThat(requested).isEqualTo(101);
        assertThat(saved)
                .extracting(InsiderReport::getInsiderCaseId)
                .doesNotHaveDuplicates()
                .hasSize(101);
        assertThat(saved).allMatch(report -> report.getTriggerType() == InsiderReportTrigger.CASE_OPENED);
    }

    // 사건 생성 판을 만드는 사이 차단 판이 먼저 저장됐으면 사건 생성 판은 건너뛴다 — 늦은 옛 사실이 더 큰 판으로 맨 위에 오지 않는다 (#675 Codex 2차 리뷰)
    @Test
    void caseOpenedAfterBlocked_isSkipped() {
        givenAlerts(alert(500L, InsiderRule.INS_08), alert(501L, InsiderRule.INS_05));
        givenAccess();
        when(locker.isLocked(STAFF_ID)).thenReturn(true);

        service.generate(new InsiderCaseChanged(CASE_ID, InsiderCaseChanged.Reason.EXPORT_BLOCKED, 501L));
        service.generate(opened(500L));

        assertThat(saved)
                .extracting(InsiderReport::getReportVersion, InsiderReport::getTriggerType)
                .containsExactly(tuple(1, InsiderReportTrigger.EXPORT_BLOCKED));
        assertThat(facts(saved.getFirst()).get("accountLocked")).isEqualTo(true);
    }

    // 다른 계기 판이 같은 번호를 먼저 잡으면(동시 알림) 다시 읽어 다음 번호로 저장한다 — 어느 판도 사라지지 않는다
    @Test
    void save_versionConflict_retriesWithNextVersion() {
        givenAlerts(alert(500L, InsiderRule.INS_05));
        givenAccess();
        AtomicBoolean raced = new AtomicBoolean(false);
        doAnswer(call -> {
                    if (raced.compareAndSet(false, true)) {
                        // 이 저장 직전에 다른 알림이 1판을 먼저 커밋했다
                        store(InsiderReport.finished(
                                CASE_ID,
                                1,
                                InsiderReportTrigger.CASE_OPENED,
                                499L,
                                ReportStatus.SUCCEEDED,
                                Map.of("kind", "OTHER"),
                                T0));
                        throw new DataIntegrityViolationException("uk_ai_insider_reports_version");
                    }
                    return store(call.getArgument(0));
                })
                .when(reports)
                .saveAndFlush(any(InsiderReport.class));

        service.generate(new InsiderCaseChanged(CASE_ID, InsiderCaseChanged.Reason.EXPORT_BLOCKED, 500L));

        assertThat(saved)
                .extracting(InsiderReport::getReportVersion, InsiderReport::getTriggerType)
                .containsExactly(
                        tuple(1, InsiderReportTrigger.CASE_OPENED), tuple(2, InsiderReportTrigger.EXPORT_BLOCKED));
    }

    private InsiderReport store(InsiderReport report) {
        saved.add(report);
        return report;
    }

    private void givenAlerts(InsiderCaseAlert... alerts) {
        when(links.findByInsiderCaseIdOrderByRaisedAtAscAlertIdAsc(CASE_ID)).thenReturn(List.of(alerts));
    }

    private void givenAccess(StaffAccessRecorded... events) {
        List<Long> ids = Arrays.stream(events).map(StaffAccessRecorded::eventId).toList();
        when(evidenceReader.accessEventIds(anyCollection())).thenReturn(ids);
        if (!ids.isEmpty()) when(accessReader.findByIds(ids)).thenReturn(List.of(events));
    }

    private static InsiderCaseChanged opened(Long alertId) {
        return new InsiderCaseChanged(CASE_ID, InsiderCaseChanged.Reason.OPENED, alertId);
    }

    private static InsiderCase insiderCase() {
        InsiderCase insiderCase = InsiderCase.open(STAFF_ID, InsiderAlertSeverity.HIGH, T0);
        ReflectionTestUtils.setField(insiderCase, "id", CASE_ID);
        return insiderCase;
    }

    // 직원별 사건 — 사건 번호는 직원 번호 + 1000
    private static InsiderCase caseOf(Long staffId) {
        InsiderCase insiderCase = InsiderCase.open(staffId, InsiderAlertSeverity.HIGH, T0);
        ReflectionTestUtils.setField(insiderCase, "id", staffId + 1000);
        return insiderCase;
    }

    private static InsiderCaseAlert alertOf(Long insiderCaseId, InsiderRule rule) {
        return InsiderCaseAlert.link(
                insiderCaseId, insiderCaseId * 10, rule, InsiderAlertSeverity.HIGH, rule.label() + " 경보", T0, T0);
    }

    private static InsiderCaseAlert alert(Long alertId, InsiderRule rule) {
        return InsiderCaseAlert.link(
                CASE_ID, alertId, rule, InsiderAlertSeverity.HIGH, rule.label() + " 경보", T0.plusSeconds(alertId), T0);
    }

    private static StaffAccessRecorded access(
            Long eventId,
            StaffAccessType type,
            StaffAccessResult result,
            String customerRef,
            Long merchantId,
            Integer itemCount) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF_ID)
                .actorRole("SECURITY_STAFF")
                .accessType(type)
                .result(result)
                .merchantId(merchantId)
                .customerRef(customerRef)
                .itemCount(itemCount)
                .loginReference(LOGIN_REF)
                .occurredAt(T0.plusSeconds(eventId))
                .build();
    }

    private static StaffAccessRecorded rawView(Long eventId, Long caseId) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF_ID)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.CONTACT_RAW_VIEW)
                .result(StaffAccessResult.ALLOWED)
                .caseId(caseId)
                .merchantId(100L)
                .paymentId(caseId * 10)
                .loginReference(LOGIN_REF)
                .occurredAt(T0.plusSeconds(eventId))
                .build();
    }

    // 평소(30일) 기록 한 줄 — 허용된 거래 조회 · 내보내기
    private static StaffAccessRecorded usual(Long eventId, StaffAccessType type, String customerRef, Instant at) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF_ID)
                .actorRole("SECURITY_STAFF")
                .accessType(type)
                .result(StaffAccessResult.ALLOWED)
                .merchantId(100L)
                .customerRef(customerRef)
                .loginReference(LOGIN_REF)
                .occurredAt(at)
                .build();
    }

    private static SecurityCase securityCase(String customerRef) {
        return SecurityCase.open(100L, customerRef, CaseType.PAYMENT_FRAUD, T0);
    }

    private static StaffAccessRecorded caseAccess(Long eventId, StaffAccessResult result, Long caseId) {
        return StaffAccessRecorded.builder()
                .eventId(eventId)
                .staffId(STAFF_ID)
                .actorRole("SECURITY_STAFF")
                .accessType(StaffAccessType.CASE_DETAIL_VIEW)
                .result(result)
                .denyReason(result == StaffAccessResult.DENIED ? StaffAccessDenyReason.NOT_ASSIGNEE : null)
                .caseId(caseId)
                .loginReference(LOGIN_REF)
                .occurredAt(T0.plusSeconds(eventId))
                .build();
    }

    private static Map<String, Object> facts(InsiderReport report) {
        return asMap(report.getReportBody().get("facts"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
