package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import com.donworry.investigation.service.mock.MockPaymentInfoStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// P5 접수 결과가 늦게 · 여러 번 · 실패로 오는 경우를 확인한다 — 금융 조치 경계는 응답을 모르는(UNKNOWN) 것으로 바꿔 둔다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class HandoffReceiptTests {

    @MockitoBean
    FinancialActionGateway financialActionGateway;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseDecisionService caseDecisionService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    HandoffService handoffService;

    @Autowired
    ActionHandoffRepository actionHandoffRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockPaymentInfoStore mockPaymentInfoStore;

    Long caseId;
    UUID idempotencyKey;

    @BeforeEach
    void decideOnePayment() {
        given(financialActionGateway.request(any())).willReturn(Receipt.unknown());
        caseId = receiveHeld();
        CaseDetailResponse detail = caseQueryService.detail(caseId);
        caseDecisionService.decide(
                caseId,
                new DecisionSubmitRequest(
                        7L,
                        1,
                        detail.reports().getLast().reportId(),
                        List.of(new DecisionSubmitRequest.Item(
                                detail.payments().getFirst().casePaymentId(), Decision.ACCEPT, "사유"))));
        idempotencyKey = actionHandoffRepository
                .findByDecisionCasePaymentSecurityCaseId(caseId)
                .getFirst()
                .getIdempotencyKey();
    }

    // 접수 여부를 모르면 인계는 REQUESTED, Case는 인계 대기로 남는다
    @Test
    void unknownReceipt_keepsRequestedAndActionPending() {
        CaseDetailResponse detail = caseQueryService.detail(caseId);

        assertThat(detail.handoffs())
                .singleElement()
                .extracting(CaseDetailResponse.HandoffResponse::status)
                .isEqualTo("REQUESTED");
        assertThat(detail.securityCase().status()).isEqualTo("ACTION_PENDING");
        assertThat(incompletePublications(detail.handoffs().getFirst().handoffId()))
                .isEqualTo(1);
    }

    // 금융 조치 호출이 예외로 끝나도 판정은 저장되고, 인계는 REQUESTED · 발행 기록은 미완료로 남아 다시 보낼 수 있다
    @Test
    void gatewayThrows_keepsRequestedAndLeavesPublicationIncomplete() {
        given(financialActionGateway.request(any())).willThrow(new IllegalStateException("timeout"));
        Long otherCaseId = receiveHeld();
        CaseDetailResponse before = caseQueryService.detail(otherCaseId);

        caseDecisionService.decide(
                otherCaseId,
                new DecisionSubmitRequest(
                        7L,
                        1,
                        before.reports().getLast().reportId(),
                        List.of(new DecisionSubmitRequest.Item(
                                before.payments().getFirst().casePaymentId(), Decision.REJECT, "사유"))));

        CaseDetailResponse after = caseQueryService.detail(otherCaseId);
        assertThat(after.decisions()).hasSize(1);
        assertThat(after.handoffs().getFirst().status()).isEqualTo("REQUESTED");
        assertThat(incompletePublications(after.handoffs().getFirst().handoffId()))
                .isEqualTo(1);
    }

    // 결제 정보가 있어야 목 보고서가 SUCCEEDED로 만들어진다 (없으면 FAILED)
    private Long receiveHeld() {
        Long paymentId = mockPaymentInfoStore
                .registerHeld(100_000L, 65, List.of("NEW_CARD"), Instant.now())
                .paymentId();
        return caseIntakeService.receive(
                new HeldPayment(paymentId, 1L, "customer-" + UUID.randomUUID(), 100_000L, "AUTH", null, Instant.now()));
    }

    private Integer incompletePublications(Long handoffId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM EVENT_PUBLICATION
                WHERE COMPLETION_DATE IS NULL
                  AND EVENT_TYPE LIKE '%HandoffRequested'
                  AND SERIALIZED_EVENT LIKE ?
                """, Integer.class, "%\"handoffId\":" + handoffId + "}%");
    }

    // 같은 접수 결과가 두 번 와도 이력은 한 번만 남고 Case는 종료된다
    @Test
    void acceptedReceiptTwice_appliesOnceAndClosesCase() {
        handoffService.applyReceipt(idempotencyKey, Receipt.accepted(55L));
        handoffService.applyReceipt(idempotencyKey, Receipt.accepted(55L));

        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(detail.history())
                .extracting(CaseDetailResponse.HistoryResponse::eventType)
                .containsOnlyOnce("HANDOFF_ACCEPTED", "CASE_CLOSED");
    }

    // 접수 거절이 확인되면 인계 실패 · Case ACTION_FAILED, 이후 접수가 확인되면 종료된다
    @Test
    void rejectedThenAccepted_movesFailedThenClosed() {
        handoffService.applyReceipt(idempotencyKey, Receipt.rejected("PAYMENT_NOT_HELD"));

        CaseDetailResponse failed = caseQueryService.detail(caseId);
        assertThat(failed.securityCase().status()).isEqualTo("ACTION_FAILED");
        assertThat(failed.handoffs().getFirst().lastErrorCode()).isEqualTo("PAYMENT_NOT_HELD");

        handoffService.applyReceipt(idempotencyKey, Receipt.accepted(56L));

        assertThat(caseQueryService.detail(caseId).securityCase().status()).isEqualTo("CLOSED");
    }
}
