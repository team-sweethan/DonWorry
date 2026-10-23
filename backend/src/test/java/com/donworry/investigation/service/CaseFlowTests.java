package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.DemoHeldPaymentResponse;
import com.donworry.investigation.repository.CasePaymentRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

// HELD 결제 → Case 생성 · 병합 → 목 보고서 → 판정 → 목 인계 접수 → Case CLOSED 를 HTTP로 확인한다
// 커밋 뒤 리스너가 돌아야 하므로 롤백 테스트가 아니다. 테스트마다 고객 참조를 새로 만들어 서로 섞이지 않게 한다
@Tag("mariadb")
@SpringBootTest
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

    // 모든 결제를 판정하면 인계가 접수되고 Case가 종료된다 (ACCEPT · REJECT 혼합)
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

    private DemoHeldPaymentResponse createHeld(String customerRef, long amount, int riskScore) throws Exception {
        String body = mvc.perform(post("/api/demo/held-payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "merchantId",
                                1,
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
        body.put("staffId", 7);
        body.put("evidenceVersion", evidenceVersion);
        body.put("reportId", reportId);
        body.put("decisions", items);
        return post("/api/security-cases/{caseId}/decisions", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
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
