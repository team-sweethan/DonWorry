package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseDetailResponse.CasePaymentResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HistoryResponse;
import com.donworry.investigation.service.CustomerVoidReader.CustomerVoid;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

// 고객 HELD 승인 취소를 Case에 반영하는 흐름 (#382, docs/payment/382-held-cancellation.md 13절)
// P5 원본은 테스트 대역(TestPaymentInfoStore.markCustomerVoided)이다. 실제 P5 발행 → P3 수신은 PaymentCustomerVoidCaseMariaDbTests가 본다
// 정책(2026-11-02): 모두 취소 → Case 종료(사유 CUSTOMER_CANCELED), 일부 취소 → 그 결제만 판정 대상에서 빼고 계속,
// 이미 판정된 결제 → 판정 · 인계는 보존하고 고객 취소로 표시
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import({MariaDbTestContainerConfig.class, TestPaymentInfoStore.class, TestFinancialActionGateway.class})
class CustomerVoidFlowTests {

    static final long MERCHANT_ID = 1L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    CustomerVoidService customerVoidService;

    @Autowired
    MerchantCaseQueryService merchantCaseQueryService;

    @Autowired
    TestPaymentInfoStore payments;

    @MockitoBean
    CurrentStaff currentStaff;

    // 보고서 요청 실패를 흉내 내는 테스트만 바꾼다. 테스트가 끝나면 원래 동작으로 돌아간다
    @MockitoSpyBean
    ReportGateway reportGateway;

    @Autowired
    com.donworry.investigation.repository.CasePaymentRepository casePaymentRepository;

    @BeforeEach
    void loginAsSecurityStaff() {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(
                        StaffSummary.builder().userId(7L).role("SECURITY_STAFF").build()));
    }

    // 결제가 하나뿐인 Case에서 고객이 취소하면 판정할 결제가 없어 Case가 닫힌다. 같은 이벤트가 다시 와도 한 번만 반영된다
    @Test
    void apply_onlyPaymentCanceled_closesCaseOnce() {
        Held held = createHeld(customer(), 300_000L);
        CustomerVoid canceled = payments.markCustomerVoided(MERCHANT_ID, held.paymentId(), Instant.now());

        customerVoidService.apply(canceled);
        customerVoidService.apply(canceled);

        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(detail.securityCase().closedAt()).isNotNull();
        assertThat(detail.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.customerCanceledAt()).isEqualTo(canceled.completedAt());
            assertThat(payment.paymentStatus()).isEqualTo("VOIDED");
        });
        assertThat(detail.history())
                .filteredOn(history -> history.eventType().equals("PAYMENT_CANCELED_BY_CUSTOMER"))
                .singleElement()
                .satisfies(history -> assertThat(((Number) history.eventData().get("cancellationId")).longValue())
                        .isEqualTo(canceled.cancellationId()));
        assertThat(closedReason(detail)).isEqualTo("CUSTOMER_CANCELED");
        assertThat(detail.decisions()).isEmpty();
    }

    // 결제 둘 중 하나만 고객이 취소하면 Case는 계속되고, 취소된 결제는 판정할 수 없다. 나머지 결제만 판정하면 종료된다
    @Test
    void apply_onePaymentOfTwo_excludesItAndClosesAfterOtherIsDecided() throws Exception {
        String customer = customer();
        Held first = createHeld(customer, 300_000L);
        Held second = createHeld(customer, 500_000L);
        assertThat(second.caseId()).isEqualTo(first.caseId());

        customerVoidService.apply(payments.markCustomerVoided(MERCHANT_ID, first.paymentId(), Instant.now()));

        CaseDetailResponse detail = caseQueryService.detail(first.caseId());
        assertThat(detail.securityCase().status()).isNotEqualTo("CLOSED");
        CasePaymentResponse canceled = payment(detail, first.paymentId());
        CasePaymentResponse open = payment(detail, second.paymentId());
        assertThat(canceled.customerCanceledAt()).isNotNull();
        assertThat(open.customerCanceledAt()).isNull();

        perform(decide(detail, List.of(item(canceled.casePaymentId(), "REJECT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_016"));
        perform(decide(detail, List.of(item(open.casePaymentId(), "ACCEPT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityCase.status").value("ACTION_PENDING"));

        recheckAll(first.caseId());

        CaseDetailResponse closed = caseQueryService.detail(first.caseId());
        assertThat(closed.securityCase().status()).isEqualTo("CLOSED");
        assertThat(closed.decisions())
                .singleElement()
                .satisfies(decision -> assertThat(decision.casePaymentId()).isEqualTo(open.casePaymentId()));
        assertThat(closedReason(closed)).isNull();
    }

    // 취소 이벤트가 아직 반영되기 전이어도 P5 원본에 고객 취소가 있으면 판정 · 인계를 만들지 않는다
    @Test
    void decide_whenCanceledInP5ButNotYetReflected_isConflict() throws Exception {
        String customer = customer();
        Held first = createHeld(customer, 300_000L);
        createHeld(customer, 500_000L);
        payments.markCustomerVoided(MERCHANT_ID, first.paymentId(), Instant.now());

        CaseDetailResponse detail = caseQueryService.detail(first.caseId());
        perform(decide(detail, List.of(item(payment(detail, first.paymentId()).casePaymentId(), "ACCEPT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_016"));
        assertThat(caseQueryService.detail(first.caseId()).decisions()).isEmpty();
    }

    // 취소가 HELD 연결보다 먼저 확정됐어도(순서 역전) 연결 커밋 직후 확인이 반영한다 — 이벤트를 놓쳐도 빠지지 않는다
    @Test
    void receive_afterCustomerAlreadyCanceled_reflectsOnLink() {
        Instant now = Instant.now();
        Long paymentId = payments.registerHeld(300_000L, 65, TestPaymentInfoStore.DEFAULT_RULES, now)
                .paymentId();
        CustomerVoid canceled = payments.markCustomerVoided(MERCHANT_ID, paymentId, now);

        Long caseId = caseIntakeService.receive(
                new HeldPayment(paymentId, MERCHANT_ID, customer(), 300_000L, "TEST-AUTH", null, now));

        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(detail.payments())
                .singleElement()
                .satisfies(payment -> assertThat(payment.customerCanceledAt()).isEqualTo(canceled.completedAt()));

        // 늦게 온 이벤트는 같은 cancellationId라 다시 기록하지 않는다
        customerVoidService.apply(canceled);
        assertThat(caseQueryService.detail(caseId).history())
                .filteredOn(history -> history.eventType().equals("PAYMENT_CANCELED_BY_CUSTOMER"))
                .hasSize(1);
    }

    // 연결 직후 보고서 요청이 실패해도 고객 취소 확인은 따로 돌아 반영된다 (PR #436 리뷰)
    @Test
    void receive_whenReportRequestFails_stillReflectsCustomerVoid() {
        doThrow(new IllegalStateException("report store down"))
                .when(reportGateway)
                .request(anyLong(), anyInt());
        Instant now = Instant.now();
        Long paymentId = payments.registerHeld(300_000L, 65, TestPaymentInfoStore.DEFAULT_RULES, now)
                .paymentId();
        payments.markCustomerVoided(MERCHANT_ID, paymentId, now);

        try {
            caseIntakeService.receive(
                    new HeldPayment(paymentId, MERCHANT_ID, customer(), 300_000L, "TEST-AUTH", null, now));
        } catch (RuntimeException ignored) {
            // 커밋 뒤 리스너 예외가 호출자에게 전달될 수 있다 — 연결은 이미 커밋됐다
        }

        Long caseId = casePaymentRepository
                .findByPaymentId(paymentId)
                .orElseThrow()
                .getSecurityCase()
                .getId();
        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.reports()).isEmpty();
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(detail.payments().getFirst().customerCanceledAt()).isNotNull();
    }

    // 이미 판정 · 인계한 결제를 고객이 취소하면 판정은 보존하고 고객 취소를 따로 남긴다. 결제가 그것뿐이면 Case는 닫힌다
    @Test
    void apply_afterDecision_keepsDecisionAndClosesCase() throws Exception {
        Held held = createHeld(customer(), 300_000L);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        perform(decide(detail, List.of(item(detail.payments().getFirst().casePaymentId(), "ACCEPT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityCase.status").value("ACTION_PENDING"));

        customerVoidService.apply(payments.markCustomerVoided(MERCHANT_ID, held.paymentId(), Instant.now()));

        CaseDetailResponse closed = caseQueryService.detail(held.caseId());
        assertThat(closed.securityCase().status()).isEqualTo("CLOSED");
        assertThat(closed.decisions())
                .singleElement()
                .satisfies(decision -> assertThat(decision.decision()).isEqualTo("ACCEPT"));
        assertThat(closed.handoffs())
                .singleElement()
                .satisfies(handoff -> assertThat(handoff.status()).isEqualTo("REQUESTED"));
        assertThat(closed.payments().getFirst().customerCanceledAt()).isNotNull();
        assertThat(closedReason(closed)).isEqualTo("CUSTOMER_CANCELED");

        // 조치 내역 · 고객사 조치 결과도 고객 취소를 구분한다 (#439)
        assertThat(caseQueryService.handoffs())
                .filteredOn(item -> item.paymentId().equals(held.paymentId()))
                .singleElement()
                .satisfies(item -> assertThat(item.customerCanceledAt())
                        .isEqualTo(closed.payments().getFirst().customerCanceledAt()));
        assertThat(merchantCaseQueryService.detail(MERCHANT_ID, held.caseId()).payments())
                .singleElement()
                .satisfies(payment -> {
                    assertThat(payment.customerCanceled()).isTrue();
                    assertThat(payment.decision()).isEqualTo("ACCEPT");
                });
    }

    // 고객 취소가 없는 결제는 조치 내역 · 고객사 결과에서 고객 취소로 보이지 않는다 (#439)
    @Test
    void views_withoutCustomerVoid_showNotCanceled() throws Exception {
        Held held = createHeld(customer(), 300_000L);
        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        perform(decide(detail, List.of(item(detail.payments().getFirst().casePaymentId(), "REJECT"))))
                .andExpect(status().isOk());

        assertThat(caseQueryService.handoffs())
                .filteredOn(item -> item.paymentId().equals(held.paymentId()))
                .singleElement()
                .satisfies(item -> assertThat(item.customerCanceledAt()).isNull());
        assertThat(merchantCaseQueryService.detail(MERCHANT_ID, held.caseId()).payments())
                .singleElement()
                .satisfies(payment -> assertThat(payment.customerCanceled()).isFalse());
    }

    // 결제가 아직 Case에 없으면 예외로 끝난다 — 리스너 publication이 미완료로 남아 재전달 · 대조 대상이 된다
    @Test
    void apply_whenPaymentNotLinked_throws() {
        Long paymentId = payments.registerHeld(300_000L, 65, TestPaymentInfoStore.DEFAULT_RULES, Instant.now())
                .paymentId();
        CustomerVoid canceled = payments.markCustomerVoided(MERCHANT_ID, paymentId, Instant.now());

        assertThatThrownBy(() -> customerVoidService.apply(canceled)).isInstanceOf(IllegalStateException.class);
    }

    // 이벤트 값이 P5 원본과 다르거나(금액) 원본이 없거나 가맹점이 다르면 반영하지 않는다
    @Test
    void apply_whenEventDiffersFromSource_throwsWithoutHistory() {
        Held held = createHeld(customer(), 300_000L);
        CustomerVoid source = payments.markCustomerVoided(MERCHANT_ID, held.paymentId(), Instant.now());

        assertThatThrownBy(() -> customerVoidService.apply(new CustomerVoid(
                        source.cancellationId(),
                        source.paymentId(),
                        source.merchantId(),
                        source.amount() + 1,
                        source.completedAt())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customerVoidService.apply(new CustomerVoid(
                        source.cancellationId(),
                        source.paymentId(),
                        MERCHANT_ID + 1,
                        source.amount(),
                        source.completedAt())))
                .isInstanceOf(IllegalStateException.class);

        CaseDetailResponse detail = caseQueryService.detail(held.caseId());
        assertThat(detail.history())
                .extracting(HistoryResponse::eventType)
                .doesNotContain("PAYMENT_CANCELED_BY_CUSTOMER");
        assertThat(detail.securityCase().status()).isNotEqualTo("CLOSED");
    }

    private Held createHeld(String customerRef, long amount) {
        Instant now = Instant.now();
        Long paymentId = payments.registerHeld(amount, 65, TestPaymentInfoStore.DEFAULT_RULES, now)
                .paymentId();
        Long caseId = caseIntakeService.receive(
                new HeldPayment(paymentId, MERCHANT_ID, customerRef, amount, "TEST-AUTH-" + paymentId, null, now));
        return new Held(paymentId, caseId);
    }

    record Held(Long paymentId, Long caseId) {}

    private static CasePaymentResponse payment(CaseDetailResponse detail, Long paymentId) {
        return detail.payments().stream()
                .filter(payment -> payment.paymentId().equals(paymentId))
                .findFirst()
                .orElseThrow();
    }

    private static Object closedReason(CaseDetailResponse detail) {
        return detail.history().stream()
                .filter(history -> history.eventType().equals("CASE_CLOSED"))
                .findFirst()
                .orElseThrow()
                .eventData()
                .get("reason");
    }

    private void recheckAll(Long caseId) throws Exception {
        for (CaseDetailResponse.HandoffResponse handoff :
                caseQueryService.detail(caseId).handoffs()) {
            perform(post("/api/security-cases/{caseId}/handoffs/{handoffId}/recheck", caseId, handoff.handoffId())
                            .with(csrf()))
                    .andExpect(status().isOk());
        }
    }

    private MockHttpServletRequestBuilder decide(CaseDetailResponse detail, List<Map<String, Object>> items)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("evidenceVersion", detail.securityCase().evidenceVersion());
        body.put("reportId", detail.reports().getLast().reportId());
        body.put("decisions", items);
        return post(
                        "/api/security-cases/{caseId}/decisions",
                        detail.securityCase().caseId())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
    }

    private static Map<String, Object> item(Long casePaymentId, String decision) {
        return Map.of("casePaymentId", casePaymentId, "decision", decision, "reason", "판정 사유");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(SessionUserTestSupport.asCurrentStaff(request, currentStaff));
    }

    private static String customer() {
        return "customer-" + UUID.randomUUID();
    }
}
