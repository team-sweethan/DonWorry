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
import com.donworry.fds.dto.InsiderAlertDetailResponse;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertEvidence;
import com.donworry.fds.entity.InsiderAlertStatus;
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
import com.donworry.identity.api.StaffSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private final InsiderDetectionProperties properties = InsiderDetectionProperties.builder()
            .ins01Window(Duration.ofMinutes(10))
            .ins01CautionCases(3)
            .ins03Window(Duration.ofHours(1))
            .ins03CautionRawViews(3)
            .build();
    private final InsiderDetectionService service =
            new InsiderDetectionService(alerts, evidence, reader, publisher, properties);

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
            return records.stream()
                    .filter(r ->
                            !r.occurredAt().isBefore(from) && !r.occurredAt().isAfter(to))
                    .toList();
        });
        when(reader.findByIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return records.stream().filter(r -> ids.contains(r.eventId())).toList();
        });
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
