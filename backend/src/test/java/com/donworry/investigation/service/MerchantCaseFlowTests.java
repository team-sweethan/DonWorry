package com.donworry.investigation.service;

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
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// 고객사 "보안 조치 결과" API — 가맹점 범위 · 접근 권한 · 내보내지 않는 항목을 HTTP로 확인한다 (#205)
// Case는 시연 HELD 결제로 만들고, 판정은 보안 직원으로 로그인을 바꿔 보안 직원 API로 한다
// 테스트마다 가맹점 ID를 새로 만들어 다른 테스트의 Case가 목록 · 지표에 섞이지 않게 한다
// 이 테스트만 쓰는 설정이라 컨텍스트가 따로 뜬다 — 끝나면 닫아 공유 MariaDB의 연결 한도를 넘지 않는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
class MerchantCaseFlowTests {

    private static final AtomicLong MERCHANT_IDS = new AtomicLong(2_000_000_000L + System.nanoTime() % 1_000_000L);

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoBean
    CurrentStaff currentStaff;

    long merchantId;

    @BeforeEach
    void newMerchant() {
        merchantId = MERCHANT_IDS.incrementAndGet();
    }

    // 목록에는 내 가맹점 Case만 나오고, 지표는 조사 중 Case 수와 ACCEPT · REJECT 판정 건수다
    @Test
    void list_returnsOnlyMyCasesWithOverview() throws Exception {
        String customer = customer();
        DemoHeldPaymentResponse decided = createHeld(merchantId, customer, 300_000L, 65);
        createHeld(merchantId, customer, 500_000L, 72);
        decideAll(decided.caseId(), "ACCEPT", "REJECT");
        DemoHeldPaymentResponse waiting = createHeld(merchantId, customer(), 100_000L, 60);
        DemoHeldPaymentResponse others = createHeld(MERCHANT_IDS.incrementAndGet(), customer(), 100_000L, 60);

        loginAsOwner(merchantId);
        mvc.perform(get("/api/merchant/security-cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.investigatingCount").value(1))
                .andExpect(jsonPath("$.overview.acceptDecisionCount").value(1))
                .andExpect(jsonPath("$.overview.rejectDecisionCount").value(1))
                .andExpect(jsonPath("$.cases.length()").value(2))
                .andExpect(jsonPath("$.cases[0].caseId").value(waiting.caseId()))
                .andExpect(jsonPath("$.cases[0].displayStatus").value("INVESTIGATING"))
                .andExpect(jsonPath("$.cases[0].paymentCount").value(1))
                .andExpect(jsonPath("$.cases[1].caseId").value(decided.caseId()))
                .andExpect(jsonPath("$.cases[1].displayStatus").value("CLOSED"))
                .andExpect(jsonPath("$.cases[1].paymentCount").value(2))
                .andExpect(
                        jsonPath("$.cases[?(@.caseId == %d)]", others.caseId()).isEmpty())
                .andExpect(jsonPath("$.cases[0].customerRef").doesNotExist())
                .andExpect(jsonPath("$.cases[0].totalAmount").doesNotExist())
                .andExpect(jsonPath("$.cases[0].maxRiskScore").doesNotExist());
    }

    // 상세는 보고서 핵심 요약 · 결제별 AI 결론 · 직원 판정 · P5 결제 상태를 따로 보내고, 내부 근거는 보내지 않는다
    @Test
    void detail_beforeAndAfterDecision_separatesDecisionAndPaymentStatus() throws Exception {
        DemoHeldPaymentResponse held = createHeld(merchantId, customer(), 300_000L, 65);

        loginAsOwner(merchantId);
        mvc.perform(get("/api/merchant/security-cases/{caseId}", held.caseId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayStatus").value("INVESTIGATING"))
                .andExpect(jsonPath("$.summary.length()").value(2))
                .andExpect(jsonPath("$.directInvestigation").value(false))
                .andExpect(jsonPath("$.payments[0].paymentId").value(held.paymentId()))
                .andExpect(jsonPath("$.payments[0].amount").value(300_000))
                .andExpect(jsonPath("$.payments[0].paymentStatus").value("HELD"))
                .andExpect(jsonPath("$.payments[0].decision").doesNotExist())
                .andExpect(jsonPath("$.payments[0].aiRecommendation").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.payments[0].riskScore").doesNotExist())
                .andExpect(jsonPath("$.payments[0].ruleHits").doesNotExist())
                .andExpect(jsonPath("$.payments[0].casePaymentId").doesNotExist())
                .andExpect(jsonPath("$.reports").doesNotExist())
                .andExpect(jsonPath("$.evidence").doesNotExist());

        decideAll(held.caseId(), "REJECT");

        loginAsOwner(merchantId);
        mvc.perform(get("/api/merchant/security-cases/{caseId}", held.caseId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayStatus").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.payments[0].decision").value("REJECT"))
                .andExpect(jsonPath("$.payments[0].paymentStatus").value("HELD"))
                .andExpect(jsonPath("$.payments[0].reason").doesNotExist())
                .andExpect(jsonPath("$.payments[0].staffId").doesNotExist())
                .andExpect(jsonPath("$.decisions").doesNotExist())
                .andExpect(jsonPath("$.handoffs").doesNotExist());
    }

    // AI 보고서가 최종 실패해 직원이 직접 조사하고 판정한 Case는 요약 없이 직접 조사로 표시된다
    @Test
    void detail_manualDecision_marksDirectInvestigation() throws Exception {
        // P5 payments에 없는 결제라 결제 정보가 비고, 목 보고서는 최종 실패(FAILED)가 된다
        Long caseId = caseIntakeService.receive(
                new HeldPayment(System.nanoTime(), merchantId, customer(), 100_000L, "AUTH", null, Instant.now()));
        loginAs(7L, "SECURITY_STAFF", null, List.of());
        CaseDetailResponse detail = caseQueryService.detail(caseId);
        mvc.perform(decide(caseId, 1, null, detail.payments().getFirst().casePaymentId(), "ACCEPT"))
                .andExpect(status().isOk());

        loginAsOwner(merchantId);
        mvc.perform(get("/api/merchant/security-cases/{caseId}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.directInvestigation").value(true))
                .andExpect(jsonPath("$.payments[0].decision").value("ACCEPT"))
                .andExpect(jsonPath("$.payments[0].aiRecommendation").doesNotExist())
                .andExpect(jsonPath("$.payments[0].amount").doesNotExist());
    }

    // 결제가 병합돼 증거 버전이 올랐는데 새 보고서가 아직 없으면 이전 버전 요약 · AI 결론을 내보내지 않는다 (PR #207 리뷰)
    @Test
    void detail_evidenceChangedBeforeNewReport_hidesOldSummary() throws Exception {
        DemoHeldPaymentResponse held = createHeld(merchantId, customer(), 300_000L, 65);
        // 새 보고서 요청 없이 증거 버전만 올려 "병합 직후 · 재조사 결과 전"을 만든다
        transactionTemplate.executeWithoutResult(status ->
                securityCaseRepository.findById(held.caseId()).orElseThrow().increaseEvidenceVersion());

        loginAsOwner(merchantId);
        mvc.perform(get("/api/merchant/security-cases/{caseId}", held.caseId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.payments[0].aiRecommendation").doesNotExist());
    }

    // 다른 가맹점 Case는 없는 Case와 같은 404다
    @Test
    void detail_otherMerchantCase_returnsNotFound() throws Exception {
        DemoHeldPaymentResponse others = createHeld(MERCHANT_IDS.incrementAndGet(), customer(), 100_000L, 60);

        loginAsOwner(merchantId);
        mvc.perform(get("/api/merchant/security-cases/{caseId}", others.caseId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INV_001"));
    }

    @Test
    void readApis_withoutLogin_returnUnauthorized() throws Exception {
        DemoHeldPaymentResponse held = createHeld(merchantId, customer(), 100_000L, 60);
        given(currentStaff.findCurrent()).willReturn(Optional.empty());

        mvc.perform(get("/api/merchant/security-cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INV_010"));
        mvc.perform(get("/api/merchant/security-cases/{caseId}", held.caseId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INV_010"));
    }

    // 보안 직원 · 관리자는 각자 화면을 쓴다. 가맹점이 있어도 사장님 전용 API는 막는다
    @ParameterizedTest
    @ValueSource(strings = {"SECURITY_STAFF", "ADMIN"})
    void readApis_byNonOwner_returnForbidden(String role) throws Exception {
        DemoHeldPaymentResponse held = createHeld(merchantId, customer(), 100_000L, 60);
        loginAs(99L, role, merchantId, List.of("FDS_VIEW", "FINANCE_VIEW"));

        expectForbidden(held.caseId());
    }

    // FDS 조회 권한이 없는 사장님 (자금관리만 이용)
    @Test
    void readApis_ownerWithoutFdsView_returnForbidden() throws Exception {
        DemoHeldPaymentResponse held = createHeld(merchantId, customer(), 100_000L, 60);
        loginAs(99L, "OPERATOR", merchantId, List.of("FINANCE_VIEW"));

        expectForbidden(held.caseId());
    }

    // 가맹점이 없는 사장님은 비정상 데이터라 전체 조회로 넓히지 않고 거부한다 (ADR-0020)
    @Test
    void readApis_ownerWithoutMerchant_returnForbidden() throws Exception {
        DemoHeldPaymentResponse held = createHeld(merchantId, customer(), 100_000L, 60);
        loginAs(99L, "OPERATOR", null, List.of("FDS_VIEW"));

        expectForbidden(held.caseId());
    }

    private void expectForbidden(Long caseId) throws Exception {
        mvc.perform(get("/api/merchant/security-cases"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_014"));
        mvc.perform(get("/api/merchant/security-cases/{caseId}", caseId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_014"));
    }

    // 결제를 연결 순서대로 하나씩 판정한다 — 보안 직원 API로 최신 보고서를 참고한 판정
    private void decideAll(Long caseId, String... decisions) throws Exception {
        loginAs(7L, "SECURITY_STAFF", null, List.of());
        CaseDetailResponse detail = caseQueryService.detail(caseId);
        Long reportId = detail.reports().getLast().reportId();
        for (int i = 0; i < decisions.length; i++) {
            mvc.perform(decide(
                            caseId,
                            detail.securityCase().evidenceVersion(),
                            reportId,
                            detail.payments().get(i).casePaymentId(),
                            decisions[i]))
                    .andExpect(status().isOk());
        }
    }

    private RequestBuilder decide(
            Long caseId, int evidenceVersion, Long reportId, Long casePaymentId, String decision) {
        Map<String, Object> body = new HashMap<>();
        body.put("evidenceVersion", evidenceVersion);
        body.put("reportId", reportId);
        body.put(
                "decisions",
                List.of(Map.of("casePaymentId", casePaymentId, "decision", decision, "reason", "시연 판정 사유")));
        return post("/api/security-cases/{caseId}/decisions", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
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

    private void loginAsOwner(long merchantId) {
        loginAs(50L, "OPERATOR", merchantId, List.of("FDS_VIEW"));
    }

    private void loginAs(Long userId, String role, Long merchantId, List<String> permissions) {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(StaffSummary.builder()
                        .userId(userId)
                        .role(role)
                        .merchantId(merchantId)
                        .permissions(permissions)
                        .build()));
    }

    private static String customer() {
        return "customer-" + UUID.randomUUID();
    }
}
