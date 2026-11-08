package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.report.entity.InsiderReport;
import com.donworry.investigation.report.entity.InsiderReportTrigger;
import com.donworry.investigation.report.entity.ReportStatus;
import com.donworry.investigation.report.repository.InsiderReportRepository;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.service.InsiderCaseChanged;
import com.donworry.investigation.service.SecurityStaffGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

// 내부자 사건 해제 검토 보고서 (#675) — 사실 집계 · 판 누적 · 실패 판. 저장소는 메모리 목록으로 대신한다
// P2 근거 · P1 접근 기록 · 정지 여부는 공개 창구 대역으로 정한다
@ExtendWith(MockitoExtension.class)
class InsiderReportServiceTests {

    private static final Long CASE_ID = 10L;
    private static final Long STAFF_ID = 21L;
    private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
    private static final String CUSTOMER_A = "CUST-a1b2c3d4";
    private static final String CUSTOMER_B = "CUST-e5f6a7b8";
    private static final UUID LOGIN_REF = UUID.fromString("0f5c1d2e-3b4a-4c5d-8e9f-a0b1c2d3e4f5");

    @Mock
    InsiderCaseRepository insiderCases;

    @Mock
    InsiderCaseAlertRepository links;

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

        service.generate(opened(500L));

        assertThat(saved).hasSize(1);
        String body = String.valueOf(saved.getFirst().getReportBody());
        assertThat(body).doesNotContain(CUSTOMER_A, CUSTOMER_B, LOGIN_REF.toString());
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

    // 사실 읽기가 실패해도 예외를 밖으로 내지 않고 실패 판을 남긴다 — 사건 · 정지는 이 서비스가 건드리지 않는다
    @Test
    void generate_factsReadFails_savesFailedVersionWithoutThrowing() {
        givenAlerts(alert(500L, InsiderRule.INS_01));
        when(evidenceReader.accessEventIds(anyCollection())).thenThrow(new IllegalStateException("fds 읽기 실패"));

        assertThatCode(() -> service.generate(opened(500L))).doesNotThrowAnyException();

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(saved.getFirst().getReportBody()).containsKey("error").doesNotContainKey("facts");
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
                .containsExactly("INS-08", "INS-01");
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
