package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.DemoHeldPaymentResponse;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.mock.MockReportGateway;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

// 시연 HELD 결제 → Case 생성 · 병합 → 목 보고서 → 판정 → 목 금융 조치 완료 → Case CLOSED 를 HTTP로 확인한다
// 시연 결제는 P5 payments에 없으므로 시연 설정처럼 목 금융 조치를 켠다. 실제 P5 연결은 PaymentActionFlowTests가 본다
// 커밋 뒤 리스너가 돌아야 하므로 롤백 테스트가 아니다. 테스트마다 고객 참조를 새로 만들어 서로 섞이지 않게 한다
@Tag("mariadb")
@SpringBootTest(properties = "investigation.mock.payment-action-enabled=true")
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
class CaseFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    MockReportGateway mockReportGateway;

    // 로그인 세션은 P1 공개 계약으로만 본다 — 기본은 보안 직원 7번, 권한 테스트에서만 바꾼다
    @MockitoBean
    CurrentStaff currentStaff;

    static final Long STAFF_ID = 7L;

    @BeforeEach
    void loginAsSecurityStaff() {
        loginAs(STAFF_ID, "SECURITY_STAFF");
    }

    // 같은 결제가 동시에 처음 두 번 와도 둘 다 같은 Case를 돌려받고 연결은 한 번만 된다
    @Test
    void receive_samePaymentConcurrently_bothSucceedWithOneLink() throws Exception {
        HeldPayment heldPayment =
                new HeldPayment(System.nanoTime(), 1L, customer(), 100_000L, "AUTH", null, Instant.now());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> receive = () -> {
                start.await();
                return caseIntakeService.receive(heldPayment);
            };
            Future<Long> first = executor.submit(receive);
            Future<Long> second = executor.submit(receive);
            start.countDown();

            Long firstCaseId = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(firstCaseId);
            assertThat(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(firstCaseId))
                    .hasSize(1);
        }
    }

    // 같은 고객의 서로 다른 결제가 동시에 처음 와도 Case는 하나로 모인다 (fds 정본 03 §3.4 생성 경쟁)
    @Test
    void receive_differentPaymentsSameCustomerConcurrently_shareOneCase() throws Exception {
        String customer = customer();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> first = () -> {
                start.await();
                return caseIntakeService.receive(
                        new HeldPayment(System.nanoTime(), 1L, customer, 100_000L, "AUTH", null, Instant.now()));
            };
            Callable<Long> second = () -> {
                start.await();
                return caseIntakeService.receive(
                        new HeldPayment(System.nanoTime() + 1, 1L, customer, 200_000L, "AUTH", null, Instant.now()));
            };
            Future<Long> firstCase = executor.submit(first);
            Future<Long> secondCase = executor.submit(second);
            start.countDown();

            Long caseId = firstCase.get(30, TimeUnit.SECONDS);
            assertThat(secondCase.get(30, TimeUnit.SECONDS)).isEqualTo(caseId);
            assertThat(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId))
                    .hasSize(2);
        }
    }

    // 보고서 요청이 늦게 도착해 Case 증거 버전이 이미 앞섰으면 낡은 버전 보고서를 만들지 않는다
    @Test
    void mockReport_staleRequest_isSkipped() {
        SecurityCase securityCase =
                securityCaseRepository.save(SecurityCase.open(1L, customer(), CaseType.PAYMENT_FRAUD, Instant.now()));
        casePaymentRepository.save(CasePayment.link(securityCase, System.nanoTime(), Instant.now()));
        casePaymentRepository.save(CasePayment.link(securityCase, System.nanoTime(), Instant.now()));
        securityCase.increaseEvidenceVersion();
        securityCaseRepository.save(securityCase);

        mockReportGateway.request(securityCase.getId(), 1);
        assertThat(mockReportGateway.findByCase(securityCase.getId())).isEmpty();

        mockReportGateway.request(securityCase.getId(), 2);
        assertThat(mockReportGateway.findByCase(securityCase.getId()))
                .singleElement()
                .extracting(ReportGateway.ReportSnapshot::evidenceVersion)
                .isEqualTo(2);
    }

    // 현재 증거 버전 보고서가 없으면(예: 목 보고서가 재시작으로 비었을 때) 다시 요청해 판정을 이어 갈 수 있다
    // 이미 있으면 두 번 요청해도 보고서는 하나다
    @Test
    void requestReport_missingReport_createsCurrentVersionOnce() throws Exception {
        SecurityCase securityCase =
                securityCaseRepository.save(SecurityCase.open(1L, customer(), CaseType.PAYMENT_FRAUD, Instant.now()));
        casePaymentRepository.save(CasePayment.link(securityCase, System.nanoTime(), Instant.now()));
        assertThat(mockReportGateway.findByCase(securityCase.getId())).isEmpty();

        mvc.perform(post("/api/security-cases/{caseId}/reports", securityCase.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(1))
                .andExpect(jsonPath("$.reports[0].evidenceVersion").value(1))
                // 연결 결제 정보를 찾지 못하면 성공 보고서를 만들지 않고 FAILED로 남긴다 (수동 판정 경로)
                .andExpect(jsonPath("$.reports[0].status").value("FAILED"))
                .andExpect(jsonPath("$.reports[0].reportBody").isEmpty());
        mvc.perform(post("/api/security-cases/{caseId}/reports", securityCase.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(1));
    }

    // 판정 항목에 null이 있으면 500이 아니라 400으로 거부한다
    @Test
    void decide_withNullItem_returnsBadRequest() throws Exception {
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(null);

        mvc.perform(decide(held.caseId(), 1, 1L, items)).andExpect(status().isBadRequest());
    }

    // 가맹점을 고르면 그 가맹점 Case만 목록에 나온다
    @Test
    void list_withMerchantId_returnsOnlyThatMerchant() throws Exception {
        long merchantId = 1_000_000L + System.nanoTime() % 1_000_000L;
        DemoHeldPaymentResponse mine = createHeld(merchantId, customer(), 300_000L, 65);
        createHeld(customer(), 300_000L, 65);

        mvc.perform(get("/api/security-cases").param("merchantId", String.valueOf(merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].caseId").value(mine.caseId()));
    }

    // HELD 결제 1건이면 Case가 생기고 보고서 v1이 나와 판정 대기가 된다
    @Test
    void heldPayment_createsCaseWithReportAndWaitsDecision() throws Exception {
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);

        CaseDetailResponse detail = caseQueryService.detail(held.caseId());

        assertThat(detail.securityCase().status()).isEqualTo("DECISION_PENDING");
        assertThat(detail.securityCase().evidenceVersion()).isEqualTo(1);
        assertThat(detail.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.paymentId()).isEqualTo(held.paymentId());
            assertThat(payment.riskScore()).isEqualTo(65);
            assertThat(payment.paymentStatus()).isEqualTo("HELD");
        });
        assertThat(detail.reports()).singleElement().satisfies(report -> {
            assertThat(report.status()).isEqualTo("SUCCEEDED");
            assertThat(report.evidenceVersion()).isEqualTo(1);
            assertThat(report.reportBody()).containsKeys("overview", "summary", "paymentAnalyses", "evidence");
        });
        assertThat(detail.history())
                .extracting(CaseDetailResponse.HistoryResponse::eventType)
                .containsExactly("CASE_CREATED", "PAYMENT_LINKED", "CASE_STATUS_CHANGED");
    }

    // 같은 고객의 두 번째 HELD 결제는 같은 Case에 병합되고 증거 버전 · 보고서 버전이 오른다
    @Test
    void secondHeldPayment_sameCustomer_mergesIntoCase() throws Exception {
        String customer = customer();
        DemoHeldPaymentResponse first = createHeld(customer, 300_000L, 65);
        DemoHeldPaymentResponse second = createHeld(customer, 500_000L, 72);

        CaseDetailResponse detail = caseQueryService.detail(first.caseId());

        assertThat(second.caseId()).isEqualTo(first.caseId());
        assertThat(detail.securityCase().evidenceVersion()).isEqualTo(2);
        assertThat(detail.payments()).hasSize(2);
        assertThat(detail.reports())
                .extracting(CaseDetailResponse.AiReportResponse::reportVersion)
                .containsExactly(1, 2);
        assertThat(detail.securityCase().status()).isEqualTo("DECISION_PENDING");
    }

    // 같은 결제가 두 번 와도 Case 연결은 한 번만 한다
    @Test
    void receive_samePaymentTwice_linksOnce() {
        HeldPayment heldPayment =
                new HeldPayment(System.nanoTime(), 1L, customer(), 100_000L, "AUTH", null, Instant.now());

        Long firstCaseId = caseIntakeService.receive(heldPayment);
        Long secondCaseId = caseIntakeService.receive(heldPayment);

        assertThat(secondCaseId).isEqualTo(firstCaseId);
        assertThat(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(firstCaseId))
                .hasSize(1);
    }

    // 모든 결제를 판정하면 금융 조치가 완료되고 Case가 종료된다 (ACCEPT · REJECT 혼합)
    @Test
    void decideAllPayments_handoffsAccepted_closesCase() throws Exception {
        String customer = customer();
        DemoHeldPaymentResponse first = createHeld(customer, 300_000L, 65);
        createHeld(customer, 500_000L, 72);
        CaseDetailResponse detail = caseQueryService.detail(first.caseId());
        List<CaseDetailResponse.CasePaymentResponse> payments = detail.payments();

        mvc.perform(decide(
                        first.caseId(),
                        detail.securityCase().evidenceVersion(),
                        latestReportId(detail),
                        List.of(
                                item(payments.get(0).casePaymentId(), "ACCEPT"),
                                item(payments.get(1).casePaymentId(), "REJECT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityCase.status").value("CLOSED"))
                .andExpect(jsonPath("$.decisions.length()").value(2));

        CaseDetailResponse closed = caseQueryService.detail(first.caseId());
        assertThat(closed.handoffs())
                .extracting(CaseDetailResponse.HandoffResponse::status)
                .containsExactly("ACCEPTED", "ACCEPTED");
        assertThat(closed.handoffs())
                .extracting(CaseDetailResponse.HandoffResponse::actionType)
                .containsExactlyInAnyOrder("CAPTURE", "VOID");
        assertThat(closed.securityCase().closedAt()).isNotNull();
        assertThat(closed.history())
                .extracting(CaseDetailResponse.HistoryResponse::eventType)
                .contains("DECISION_CONFIRMED", "HANDOFF_REQUESTED", "HANDOFF_ACCEPTED", "CASE_CLOSED");
    }

    // 판정 직원은 요청 본문이 아니라 로그인 세션 사용자로 저장된다 — 본문의 staffId는 무시한다
    @Test
    void decide_savesSessionStaffAndIgnoresBodyStaffId() throws Exception {
        loginAs(42L, "SECURITY_STAFF");
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        Long casePaymentId = detail.payments().getFirst().casePaymentId();

        mvc.perform(decide(held.caseId(), 1, latestReportId(detail), List.of(item(casePaymentId, "ACCEPT"))))
                .andExpect(status().isOk());

        assertThat(caseQueryService.detail(held.caseId()).decisions())
                .extracting(CaseDetailResponse.DecisionResponse::staffId)
                .containsExactly(42L);
    }

    // 이미 완료된 인계는 다시 확인할 수 없다(409), 로그인하지 않았으면 401이다
    @Test
    void recheckHandoff_completedOrWithoutLogin_isRefused() throws Exception {
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        Long casePaymentId = detail.payments().getFirst().casePaymentId();
        mvc.perform(decide(held.caseId(), 1, latestReportId(detail), List.of(item(casePaymentId, "ACCEPT"))))
                .andExpect(status().isOk());
        Long handoffId =
                caseQueryService.detail(held.caseId()).handoffs().getFirst().handoffId();

        mvc.perform(post("/api/security-cases/{caseId}/handoffs/{handoffId}/recheck", held.caseId(), handoffId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_012"));
        given(currentStaff.findCurrent()).willReturn(Optional.empty());
        mvc.perform(post("/api/security-cases/{caseId}/handoffs/{handoffId}/recheck", held.caseId(), handoffId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INV_010"));
    }

    // 로그인하지 않았으면 401이고 판정은 저장되지 않는다
    @Test
    void decide_withoutLogin_returnsUnauthorized() throws Exception {
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        Long casePaymentId = detail.payments().getFirst().casePaymentId();
        given(currentStaff.findCurrent()).willReturn(Optional.empty());

        mvc.perform(decide(held.caseId(), 1, latestReportId(detail), List.of(item(casePaymentId, "ACCEPT"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INV_010"));

        assertThat(caseQueryService.detail(held.caseId()).decisions()).isEmpty();
    }

    // 보안 직원이 아닌 역할(사장님 · 관리자)은 403이고 판정은 저장되지 않는다
    @ParameterizedTest
    @ValueSource(strings = {"OPERATOR", "ADMIN"})
    void decide_byNonSecurityStaff_returnsForbidden(String role) throws Exception {
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        Long casePaymentId = detail.payments().getFirst().casePaymentId();
        loginAs(99L, role);

        mvc.perform(decide(held.caseId(), 1, latestReportId(detail), List.of(item(casePaymentId, "ACCEPT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_009"));

        assertThat(caseQueryService.detail(held.caseId()).decisions()).isEmpty();
    }

    // 일부만 판정하면 판정 대기로 남고, 같은 결제를 다시 판정하면 거부한다
    @Test
    void decidePartially_keepsWaitingAndRejectsSecondDecision() throws Exception {
        String customer = customer();
        DemoHeldPaymentResponse first = createHeld(customer, 300_000L, 65);
        createHeld(customer, 500_000L, 72);
        CaseDetailResponse detail = caseQueryService.detail(first.caseId());
        Long casePaymentId = detail.payments().get(0).casePaymentId();

        mvc.perform(decide(first.caseId(), 2, latestReportId(detail), List.of(item(casePaymentId, "ACCEPT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityCase.status").value("DECISION_PENDING"));
        mvc.perform(decide(first.caseId(), 2, latestReportId(detail), List.of(item(casePaymentId, "REJECT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_007"));
    }

    // 직원이 본 증거 버전이 현재와 다르면 판정을 저장하지 않는다
    @Test
    void decide_withStaleEvidenceVersion_returnsConflict() throws Exception {
        String customer = customer();
        DemoHeldPaymentResponse first = createHeld(customer, 300_000L, 65);
        createHeld(customer, 500_000L, 72);
        CaseDetailResponse detail = caseQueryService.detail(first.caseId());

        mvc.perform(decide(
                        first.caseId(),
                        1,
                        latestReportId(detail),
                        List.of(item(detail.payments().get(0).casePaymentId(), "ACCEPT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_003"));
        assertThat(caseQueryService.detail(first.caseId()).decisions()).isEmpty();
    }

    // 최신이 아닌 보고서로는 판정할 수 없다
    @Test
    void decide_withOldReport_returnsConflict() throws Exception {
        String customer = customer();
        DemoHeldPaymentResponse first = createHeld(customer, 300_000L, 65);
        createHeld(customer, 500_000L, 72);
        CaseDetailResponse detail = caseQueryService.detail(first.caseId());
        Long oldReportId = detail.reports().get(0).reportId();

        mvc.perform(decide(
                        first.caseId(),
                        2,
                        oldReportId,
                        List.of(item(detail.payments().get(0).casePaymentId(), "ACCEPT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_004"));
    }

    // 보고서가 성공했는데 보고서 없이(수동) 판정하면 거부한다
    @Test
    void decide_manualWhileReportSucceeded_returnsBadRequest() throws Exception {
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());

        mvc.perform(decide(
                        held.caseId(),
                        1,
                        null,
                        List.of(item(detail.payments().get(0).casePaymentId(), "ACCEPT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INV_005"));
    }

    // 종료된 Case에는 더 판정할 수 없다
    @Test
    void decide_onClosedCase_returnsConflict() throws Exception {
        DemoHeldPaymentResponse held = createHeld(customer(), 300_000L, 65);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        Long casePaymentId = detail.payments().get(0).casePaymentId();
        mvc.perform(decide(held.caseId(), 1, latestReportId(detail), List.of(item(casePaymentId, "ACCEPT"))))
                .andExpect(jsonPath("$.securityCase.status").value("CLOSED"));

        mvc.perform(decide(held.caseId(), 1, latestReportId(detail), List.of(item(casePaymentId, "ACCEPT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_002"));
    }

    // REVIEW 구간(50~79점) 밖의 점수는 데모 결제로 만들 수 없다
    @Test
    void demoHeldPayment_scoreOutOfReviewRange_returnsBadRequest() throws Exception {
        mvc.perform(post("/api/demo/held-payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("merchantId", 1, "customerRef", customer(), "amount", 1000, "riskScore", 80))))
                .andExpect(status().isBadRequest());
    }

    // 없는 Case는 404와 코드로 응답한다
    @Test
    void detail_unknownCase_returnsNotFound() throws Exception {
        mvc.perform(get("/api/security-cases/{caseId}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INV_001"));
    }

    // 목록에 방금 만든 Case가 결제 수 · 금액 · 최고 점수와 함께 나온다
    @Test
    void list_includesCaseSummary() throws Exception {
        String customer = customer();
        DemoHeldPaymentResponse first = createHeld(customer, 300_000L, 65);
        createHeld(customer, 500_000L, 72);

        mvc.perform(get("/api/security-cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.caseId == %d)].paymentCount", first.caseId())
                        .value(2))
                .andExpect(jsonPath("$[?(@.caseId == %d)].totalAmount", first.caseId())
                        .value(800_000))
                .andExpect(jsonPath("$[?(@.caseId == %d)].maxRiskScore", first.caseId())
                        .value(72));
    }

    // 결제 정보를 조회하지 못한 결제가 있으면 합계 · 최고 점수는 일부 값이 아니라 null이다 (서버 재시작으로 목업이 비었을 때)
    @Test
    void list_missingPaymentInfo_returnsNullTotalAmount() {
        Long caseId = caseIntakeService.receive(
                new HeldPayment(System.nanoTime(), 1L, customer(), 100_000L, "AUTH", null, Instant.now()));

        assertThat(caseQueryService.list(null))
                .filteredOn(summary -> summary.caseId().equals(caseId))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.paymentCount()).isEqualTo(1);
                    assertThat(summary.totalAmount()).isNull();
                    assertThat(summary.maxRiskScore()).isNull();
                });
    }

    private DemoHeldPaymentResponse createHeld(String customerRef, long amount, int riskScore) throws Exception {
        return createHeld(1L, customerRef, amount, riskScore);
    }

    private DemoHeldPaymentResponse createHeld(long merchantId, String customerRef, long amount, int riskScore)
            throws Exception {
        String body = mvc.perform(post("/api/demo/held-payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "merchantId",
                                merchantId,
                                "customerRef",
                                customerRef,
                                "amount",
                                amount,
                                "riskScore",
                                riskScore))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readValue(body, DemoHeldPaymentResponse.class);
    }

    private RequestBuilder decide(Long caseId, int evidenceVersion, Long reportId, List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>();
        // 예전 화면이 보내던 값 — 서버는 무시하고 로그인 세션 사용자로 저장해야 한다
        body.put("staffId", 7);
        body.put("evidenceVersion", evidenceVersion);
        body.put("reportId", reportId);
        body.put("decisions", items);
        return post("/api/security-cases/{caseId}/decisions", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
    }

    private void loginAs(Long userId, String role) {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(
                        StaffSummary.builder().userId(userId).role(role).build()));
    }

    private static Map<String, Object> item(Long casePaymentId, String decision) {
        return Map.of("casePaymentId", casePaymentId, "decision", decision, "reason", "시연 판정 사유");
    }

    private static Long latestReportId(CaseDetailResponse detail) {
        return detail.reports().getLast().reportId();
    }

    private static String customer() {
        return "customer-" + UUID.randomUUID();
    }
}
