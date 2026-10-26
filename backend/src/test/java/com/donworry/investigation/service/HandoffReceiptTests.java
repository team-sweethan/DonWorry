package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HandoffResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HistoryResponse;
import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.entity.ActionType;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.service.FinancialActionGateway.Command;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import com.donworry.investigation.service.mock.MockPaymentInfoStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// P5 결과가 응답 · 결과 이벤트 · 다시 확인으로 늦게 · 여러 번 · 어긋나게 오는 경우를 확인한다 (ADR-0027 완료 기반)
// 금융 조치 경계는 기본으로 처리 중(PENDING)을 돌려주게 바꿔 두고, 결과는 HandoffService에 직접 넣는다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class HandoffReceiptTests {

    @MockitoBean
    FinancialActionGateway financialActionGateway;

    // 판정 · 다시 확인은 로그인한 보안 직원만 할 수 있다 — 기본은 보안 직원으로 고정한다
    @MockitoBean
    CurrentStaff currentStaff;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseDecisionService caseDecisionService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    HandoffService handoffService;

    @Autowired
    HandoffRecheckService handoffRecheckService;

    @Autowired
    ActionHandoffRepository actionHandoffRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockPaymentInfoStore mockPaymentInfoStore;

    Long caseId;
    Long handoffId;
    UUID idempotencyKey;
    Command capture;

    @BeforeEach
    void decideOnePayment() {
        loginAs("SECURITY_STAFF");
        given(financialActionGateway.request(any())).willReturn(Receipt.pending());
        caseId = decidedCase(Decision.ACCEPT);
        HandoffResponse handoff = caseQueryService.detail(caseId).handoffs().getFirst();
        handoffId = handoff.handoffId();
        idempotencyKey = actionHandoffRepository
                .findByDecisionCasePaymentSecurityCaseId(caseId)
                .getFirst()
                .getIdempotencyKey();
        capture = commandOf(caseId, ActionType.CAPTURE, idempotencyKey);
    }

    // P5가 처리 중(PENDING)이라고 답하면 인계는 REQUESTED, Case는 금융 처리 대기로 남고 발행은 정상 완료된다
    @Test
    void pendingReceipt_keepsRequestedAndCompletesPublication() {
        CaseDetailResponse detail = caseQueryService.detail(caseId);

        assertThat(detail.handoffs())
                .singleElement()
                .extracting(HandoffResponse::status)
                .isEqualTo("REQUESTED");
        assertThat(detail.securityCase().status()).isEqualTo("ACTION_PENDING");
        assertThat(incompletePublications(handoffId)).isZero();
    }

    // 금융 조치 호출이 예외로 끝나도 판정은 저장되고, 인계는 REQUESTED · 발행 기록은 미완료로 남아 다시 보낼 수 있다
    @Test
    void gatewayThrows_keepsRequestedAndLeavesPublicationIncomplete() {
        given(financialActionGateway.request(any())).willThrow(new IllegalStateException("timeout"));

        Long otherCaseId = decidedCase(Decision.REJECT);

        CaseDetailResponse after = caseQueryService.detail(otherCaseId);
        assertThat(after.decisions()).hasSize(1);
        assertThat(after.handoffs().getFirst().status()).isEqualTo("REQUESTED");
        assertThat(incompletePublications(after.handoffs().getFirst().handoffId()))
                .isEqualTo(1);
    }

    // 결과 이벤트로도 오는 응답은 판정 직후 반영하지 않고, 이벤트가 오면 실제 완료 시각과 함께 반영한다
    @Test
    void receiptFollowedByResultEvent_appliesOnlyFromEventWithCompletedAt() {
        given(financialActionGateway.request(any()))
                .willReturn(Receipt.accepted(31L).withResultEvent());
        Long otherCaseId = decidedCase(Decision.REJECT);
        assertThat(caseQueryService.detail(otherCaseId).handoffs().getFirst().status())
                .isEqualTo("REQUESTED");

        Instant completedAt = Instant.parse("2026-10-26T05:00:00Z");
        handoffService.applyResult(
                commandOf(otherCaseId, ActionType.VOID, keyOf(otherCaseId)), Receipt.accepted(31L), completedAt);

        CaseDetailResponse detail = caseQueryService.detail(otherCaseId);
        assertThat(detail.handoffs().getFirst().status()).isEqualTo("ACCEPTED");
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(historyOf(detail, "HANDOFF_ACCEPTED").eventData())
                .containsEntry("completedAt", completedAt.toString())
                .containsEntry("targetOperationId", 31);
    }

    // 같은 결과 이벤트가 두 번 와도 이력은 한 번만 남고 Case는 종료된다
    @Test
    void resultEventTwice_appliesOnceAndClosesCase() {
        handoffService.applyResult(capture, Receipt.accepted(55L), Instant.now());
        handoffService.applyResult(capture, Receipt.accepted(55L), Instant.now());

        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(detail.history())
                .extracting(HistoryResponse::eventType)
                .containsOnlyOnce("HANDOFF_ACCEPTED", "CASE_CLOSED");
    }

    // 완료된 인계에 다른 작업 ID · 거절 결과가 오면 반영하지 않고 처음 결과를 지킨다
    @Test
    void conflictingResultAfterCompletion_isRefusedAndKeepsFirst() {
        handoffService.applyResult(capture, Receipt.accepted(55L), Instant.now());

        assertThatThrownBy(() -> handoffService.applyResult(capture, Receipt.accepted(56L), Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> handoffService.applyResult(capture, Receipt.rejected("VOID_DECLINED"), Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        HandoffResponse handoff = caseQueryService.detail(caseId).handoffs().getFirst();
        assertThat(handoff.status()).isEqualTo("ACCEPTED");
        assertThat(handoff.targetOperationId()).isEqualTo(55L);
    }

    // 멱등키는 같아도 결제 · 판정 · 조치 유형이 다른 결과는 반영하지 않는다
    @Test
    void resultWithMismatchedFields_isNotApplied() {
        Command wrongType = new Command(capture.paymentId(), capture.decisionId(), ActionType.VOID, idempotencyKey);
        Command wrongPayment =
                new Command(capture.paymentId() + 1, capture.decisionId(), ActionType.CAPTURE, idempotencyKey);

        assertThatThrownBy(() -> handoffService.applyResult(wrongType, Receipt.accepted(55L), Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> handoffService.applyResult(wrongPayment, Receipt.accepted(55L), Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(caseQueryService.detail(caseId).handoffs().getFirst().status())
                .isEqualTo("REQUESTED");
    }

    // VOID 확정 거절이면 인계 FAILED · Case ACTION_FAILED로 남고, 다시 확인 대상도 아니다
    @Test
    void voidDeclined_keepsActionFailedAndCannotRecheck() {
        Long voidCaseId = decidedCase(Decision.REJECT);
        Long voidHandoffId =
                caseQueryService.detail(voidCaseId).handoffs().getFirst().handoffId();

        handoffService.applyResult(
                commandOf(voidCaseId, ActionType.VOID, keyOf(voidCaseId)),
                Receipt.rejected("VOID_DECLINED"),
                Instant.now());

        assertThatThrownBy(() -> handoffRecheckService.recheck(voidCaseId, voidHandoffId))
                .extracting(e -> ((InvestigationException) e).getErrorCode())
                .isEqualTo(InvestigationErrorCode.HANDOFF_NOT_PENDING);
        CaseDetailResponse detail = caseQueryService.detail(voidCaseId);
        assertThat(detail.securityCase().status()).isEqualTo("ACTION_FAILED");
        assertThat(detail.handoffs().getFirst().lastErrorCode()).isEqualTo("VOID_DECLINED");
    }

    // 다시 확인해도 처리 중이면 REQUESTED를 유지하고, 누가 언제 확인했는지만 남긴다
    @Test
    void recheck_whenStillPending_keepsRequestedAndRecordsRecheck() {
        Instant before = caseQueryService.detail(caseId).handoffs().getFirst().lastAttemptedAt();

        handoffRecheckService.recheck(caseId, handoffId);

        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.handoffs().getFirst().status()).isEqualTo("REQUESTED");
        assertThat(detail.handoffs().getFirst().lastAttemptedAt()).isAfter(before);
        assertThat(detail.securityCase().status()).isEqualTo("ACTION_PENDING");
        HistoryResponse recheck = historyOf(detail, "HANDOFF_RECHECKED");
        assertThat(recheck.actorType()).isEqualTo("STAFF");
        assertThat(recheck.actorId()).isEqualTo(7L);
        assertThat(recheck.eventData()).containsEntry("result", "PENDING");
    }

    // 이벤트가 유실돼도 다시 확인으로 P5에 저장된 완료 결과를 받아 Case를 종료한다
    @Test
    void recheck_whenStoredResultAccepted_closesCase() {
        given(financialActionGateway.request(any()))
                .willReturn(Receipt.accepted(77L).withResultEvent());

        handoffRecheckService.recheck(caseId, handoffId);

        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.handoffs().getFirst().status()).isEqualTo("ACCEPTED");
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(historyOf(detail, "HANDOFF_ACCEPTED").eventData()).containsEntry("source", "RECHECK");
    }

    // 다른 Case의 인계 ID로는 다시 확인할 수 없다
    @Test
    void recheck_handoffOfOtherCase_throwsNotFound() {
        Long otherCaseId = decidedCase(Decision.ACCEPT);

        assertThatThrownBy(() -> handoffRecheckService.recheck(otherCaseId, handoffId))
                .extracting(e -> ((InvestigationException) e).getErrorCode())
                .isEqualTo(InvestigationErrorCode.HANDOFF_NOT_FOUND);
    }

    // P5가 응답하지 않으면 다시 확인은 503으로 끝나고 인계는 그대로다
    @Test
    void recheck_whenGatewayFails_throwsUnavailableAndKeepsRequested() {
        given(financialActionGateway.request(any())).willThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> handoffRecheckService.recheck(caseId, handoffId))
                .extracting(e -> ((InvestigationException) e).getErrorCode())
                .isEqualTo(InvestigationErrorCode.PAYMENT_ACTION_UNAVAILABLE);
        assertThat(caseQueryService.detail(caseId).history())
                .extracting(HistoryResponse::eventType)
                .doesNotContain("HANDOFF_RECHECKED");
    }

    // 보안 직원이 아니면 다시 확인할 수 없다
    @Test
    void recheck_byNonSecurityStaff_throwsForbidden() {
        loginAs("OPERATOR");

        assertThatThrownBy(() -> handoffRecheckService.recheck(caseId, handoffId))
                .extracting(e -> ((InvestigationException) e).getErrorCode())
                .isEqualTo(InvestigationErrorCode.SECURITY_STAFF_ONLY);
    }

    // 결제 정보가 있어야 목 보고서가 SUCCEEDED로 만들어진다 (없으면 FAILED)
    private Long decidedCase(Decision decision) {
        Long paymentId = mockPaymentInfoStore
                .registerHeld(100_000L, 65, List.of("NEW_CARD"), Instant.now())
                .paymentId();
        Long newCaseId = caseIntakeService.receive(
                new HeldPayment(paymentId, 1L, "customer-" + UUID.randomUUID(), 100_000L, "AUTH", null, Instant.now()));
        CaseDetailResponse detail = caseQueryService.detail(newCaseId);
        caseDecisionService.decide(
                newCaseId,
                new DecisionSubmitRequest(
                        1,
                        detail.reports().getLast().reportId(),
                        List.of(new DecisionSubmitRequest.Item(
                                detail.payments().getFirst().casePaymentId(), decision, "사유"))));
        return newCaseId;
    }

    private Command commandOf(Long targetCaseId, ActionType actionType, UUID key) {
        CaseDetailResponse detail = caseQueryService.detail(targetCaseId);
        return new Command(
                detail.payments().getFirst().paymentId(),
                detail.decisions().getFirst().decisionId(),
                actionType,
                key);
    }

    private UUID keyOf(Long targetCaseId) {
        return actionHandoffRepository
                .findByDecisionCasePaymentSecurityCaseId(targetCaseId)
                .getFirst()
                .getIdempotencyKey();
    }

    private void loginAs(String role) {
        given(currentStaff.findCurrent())
                .willReturn(
                        Optional.of(StaffSummary.builder().userId(7L).role(role).build()));
    }

    private static HistoryResponse historyOf(CaseDetailResponse detail, String eventType) {
        return detail.history().stream()
                .filter(history -> history.eventType().equals(eventType))
                .findFirst()
                .orElseThrow();
    }

    private Integer incompletePublications(Long handoffId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM EVENT_PUBLICATION
                WHERE COMPLETION_DATE IS NULL
                  AND EVENT_TYPE LIKE '%HandoffRequested'
                  AND SERIALIZED_EVENT LIKE ?
                """, Integer.class, "%\"handoffId\":" + handoffId + "}%");
    }
}
