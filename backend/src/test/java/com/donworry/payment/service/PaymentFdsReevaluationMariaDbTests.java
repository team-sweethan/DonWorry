package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
import com.donworry.identity.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.*;

@Tag("mariadb")
@SpringBootTest(properties = "payment.fds-reevaluation.execution-timeout-seconds=5")
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentFdsReevaluationMariaDbTests {
    @Autowired
    org.springframework.transaction.PlatformTransactionManager manager;

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentFdsReevaluationService flow;

    @Autowired
    PaymentFdsReevaluationAdmissionService admission;

    @Autowired
    PaymentFdsReevaluationExecutionService execution;

    @Autowired
    PaymentRequestAdmissionService initialAdmission;

    @Autowired
    PaymentRequestProcessingService processing;

    @Autowired
    PaymentFdsAnalysisService analysis;

    @Autowired
    PaymentOperationsQueryService query;

    @Autowired
    PaymentFdsRecoveryRepository recoveries;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FdsFailureReporter reporter;

    @MockitoBean
    CurrentStaff staff;

    @MockitoSpyBean
    FdsReevaluationRequester requester;

    @MockitoSpyBean
    FdsEvaluator evaluator;

    @MockitoSpyBean
    PaymentAuthorizer authorizer;

    @MockitoSpyBean
    PaymentAuthorizationService authorization;

    private final ThreadLocal<Long> actor = ThreadLocal.withInitial(() -> 101L);

    @BeforeEach
    void setup() {
        when(staff.findCurrent())
                .thenAnswer(
                        i -> Optional.of(new StaffSummary(actor.get(), "보안 직원", "SECURITY_STAFF", null, List.of())));
    }

    private long ready() {
        return ready(PaymentRiskSignals.none());
    }

    // 최초 P2 평가 행에도 P5가 보낸 식별자를 그대로 저장해, 보고 · 재평가 입력이 같은지 P2가 대조할 수 있게 한다.
    private long ready(PaymentRiskSignals signals) {
        var now = Instant.now();
        var receipt = initialAdmission.admit(new PaymentRequestCommand(
                9000000000274L,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                100L,
                now,
                UUID.randomUUID(),
                signals));
        jdbc.update(
                "INSERT INTO fds_evaluations (evaluation_request_id,payment_id,merchant_id,customer_ref,payment_requested_at,requested_amount,result,evaluated_at,error_code,device_id,card_token,card_display_label,ip_country_code,ip_address,buyer_email) SELECT ?,payment_id,merchant_id,customer_ref,created_at,amount,'ERROR',UTC_TIMESTAMP(6),'TRANSIENT_TEST',?,?,?,?,?,? FROM payments WHERE payment_id=?",
                receipt.evaluationRequestId().toString(),
                signals.deviceId(),
                signals.cardToken(),
                signals.cardDisplayLabel(),
                signals.ipCountryCode(),
                signals.ipAddress(),
                signals.buyerEmail(),
                receipt.paymentId());
        long evaluationId = jdbc.queryForObject(
                "SELECT evaluation_id FROM fds_evaluations WHERE evaluation_request_id=?",
                Long.class,
                receipt.evaluationRequestId().toString());
        var report = processing
                .saveEvaluation(
                        receipt.paymentId(),
                        new PaymentObservedEvaluation(
                                new FdsEvaluationResponse(
                                        receipt.evaluationRequestId(),
                                        evaluationId,
                                        FdsResult.ERROR,
                                        null,
                                        now,
                                        "TRANSIENT_TEST"),
                                now,
                                Instant.now()))
                .orElseThrow();
        reporter.report(report);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(recoveries
                                .findByPaymentId(receipt.paymentId())
                                .orElseThrow()
                                .getStatus())
                        .isEqualTo(PaymentFdsRecoveryStatus.READY));
        return receipt.paymentId();
    }

    private long evaluationCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=?", Long.class, id);
    }

    private long businessEvents(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE JSON_VALUE(serialized_event,'$.paymentId')=? AND event_type IN ('com.donworry.payment.api.PaymentHeld','com.donworry.settlement.api.CaptureRequested')",
                Long.class,
                id);
    }

    // 평가 오류 보고와 재평가가 시도에 저장한 FDS 식별자를 그대로 넘긴다 (#325).
    // 보고 값이 최초 평가와 다르면 P2 분석이 EVALUATION_INPUT_MISMATCH로 끝나 READY가 되지 않는다.
    @Test
    void reevaluate_withRiskSignals_reportsAndReevaluatesWithStoredSignals() {
        var signals = new PaymentRiskSignals(
                "device-325", "card-token-325", "신한 **** 1234", "KR", "203.0.113.10", "buyer-325@example.com");
        long id = ready(signals);
        assertThat(flow.reevaluate(id).status()).isEqualTo("COMPLETED");
        verify(requester)
                .reevaluate(argThat(r -> "device-325".equals(r.deviceId())
                        && "card-token-325".equals(r.cardToken())
                        && "신한 **** 1234".equals(r.cardDisplayLabel())
                        && "KR".equals(r.ipCountryCode())
                        && "203.0.113.10".equals(r.ipAddress())
                        && "buyer-325@example.com".equals(r.buyerEmail())));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=? AND device_id='device-325' AND card_token='card-token-325' AND buyer_email='buyer-325@example.com'",
                        Long.class,
                        id))
                .isEqualTo(2);
    }

    // 결과 분기용 평가 대역도 실제 P2 재평가 트랜잭션 안에서 평가 행을 저장한다.
    private void evaluationResult(FdsResult result) {
        doAnswer(i -> {
                    FdsEvaluationRequest r = i.getArgument(0);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    jdbc.update(
                            "INSERT INTO fds_evaluations (evaluation_request_id,payment_id,merchant_id,customer_ref,payment_requested_at,requested_amount,result,risk_score,evaluated_at,error_code) SELECT ?,payment_id,merchant_id,customer_ref,created_at,amount,?,?,UTC_TIMESTAMP(6),? FROM payments WHERE payment_id=?",
                            r.evaluationRequestId().toString(),
                            result.name(),
                            result == FdsResult.ERROR ? null : 60,
                            result == FdsResult.ERROR ? "RETRY_ERROR" : null,
                            r.paymentId());
                    long evaluationId = jdbc.queryForObject(
                            "SELECT evaluation_id FROM fds_evaluations WHERE evaluation_request_id=?",
                            Long.class,
                            r.evaluationRequestId().toString());
                    return new FdsEvaluationResponse(
                            r.evaluationRequestId(),
                            evaluationId,
                            result,
                            result == FdsResult.ERROR ? null : 60,
                            Instant.now(),
                            result == FdsResult.ERROR ? "RETRY_ERROR" : null);
                })
                .when(evaluator)
                .evaluate(any());
    }

    // 실제 P2 평가·계보 저장·모의 승인·이벤트와 복구 종료를 연결하고 재호출은 재실행하지 않는다.
    @Test
    void reevaluate_withRealP2_completesAndReplays() {
        long id = ready();
        var original = recoveries.findByPaymentId(id).orElseThrow();
        var first = flow.reevaluate(id);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.evaluationResult()).isEqualTo("ALLOW");
        assertThat(first.reasonCode()).isNull();
        assertThat(first.completedAt()).isNotNull();
        var saved = recoveries.findByPaymentId(id).orElseThrow();
        assertThat(evaluationCount(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=? AND evaluation_request_id=? AND original_evaluation_request_id=? AND reevaluation_retry_id=?",
                        Long.class,
                        id,
                        saved.getNewEvaluationRequestId().toString(),
                        original.getOriginalEvaluationRequestId().toString(),
                        saved.getRetryId().toString()))
                .isEqualTo(1);
        assertThat(businessEvents(id)).isEqualTo(1);
        var replay = flow.reevaluate(id);
        assertThat(replay.completedAt()).isEqualTo(first.completedAt());
        assertThat(replay.evaluationResult()).isEqualTo(first.evaluationResult());
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getRetryId()).isEqualTo(saved.getRetryId());
        assertThat(requests.findByPaymentId(id).orElseThrow().getAttemptNo()).isEqualTo(1);
        verify(requester).reevaluate(any());
        verify(authorizer).authorize(any());
        var detail = query.findPayment(id);
        assertThat(detail.evaluation().evaluationRequestId()).isEqualTo(saved.getNewEvaluationRequestId());
        assertThat(detail.evaluation().evaluationResult())
                .isEqualTo(detail.fdsRecovery().evaluationResult());
    }

    // 결과별 종료와 재요청 멱등성을 검사하며 지연 분석은 원본·종료를 보존한다.
    @ParameterizedTest
    @EnumSource(
            value = FdsResult.class,
            names = {"REVIEW", "DENY", "ERROR"})
    void reevaluate_whenResultVaries_finishesWithoutAnotherAttempt(FdsResult result) {
        long id = ready();
        evaluationResult(result);
        var before = recoveries.findByPaymentId(id).orElseThrow();
        var response = flow.reevaluate(id);
        assertThat(response.status()).isEqualTo(result == FdsResult.ERROR ? "MANUAL_REVIEW" : "COMPLETED");
        assertThat(response.paymentStatus())
                .isEqualTo(
                        switch (result) {
                            case REVIEW -> "HELD";
                            case DENY -> "DENIED";
                            default -> "PENDING";
                        });
        assertThat(response.reasonCode()).isEqualTo(result == FdsResult.ERROR ? "RETRY_ERROR" : null);
        var row = recoveries.findByPaymentId(id).orElseThrow();
        assertThat(row.getReasonCode()).isEqualTo(before.getReasonCode());
        assertThat(row.getOriginalEvaluationRequestId()).isEqualTo(before.getOriginalEvaluationRequestId());
        analysis.receive(new FdsEvaluationFailureAnalyzed(
                before.getAnalysisId(),
                before.getFailureReportId(),
                before.getOriginalEvaluationRequestId(),
                before.getOriginalEvaluationRequestId(),
                id,
                before.getDisposition(),
                before.getReasonCode(),
                before.getAnalyzedAt()));
        assertThat(flow.reevaluate(id)).isEqualTo(response);
        assertThat(evaluationCount(id)).isEqualTo(2);
        assertThat(businessEvents(id)).isEqualTo(result == FdsResult.REVIEW ? 1 : 0);
        verify(requester).reevaluate(any());
        verify(authorizer, times(result == FdsResult.REVIEW ? 1 : 0)).authorize(any());
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getCompletedAt())
                .isEqualTo(row.getCompletedAt());
    }

    // 승인 거절도 평가 처리 완료이며 결제 성공으로 표시하지 않는다.
    @Test
    void reevaluate_whenAuthorizationDeclined_completesAsDeclined() {
        long id = ready();
        doReturn(new AuthorizationResult(AuthorizationResult.Type.DECLINED, null, "MOCK_DECLINED"))
                .when(authorizer)
                .authorize(any());
        var result = flow.reevaluate(id);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.paymentStatus()).isEqualTo("DECLINED");
        assertThat(payments.findById(id).orElseThrow().getAuthorizationRef()).isNull();
        assertThat(businessEvents(id)).isZero();
        assertThat(requests.findByPaymentId(id).orElseThrow().getProcessingStage())
                .isEqualTo(PaymentProcessingStage.AUTHORIZED);
    }

    // 저장 후·승인 후·커밋 시점·응답 불일치는 실행 전체를 롤백하고 같은 작업을 재개한다.
    @ParameterizedTest
    @ValueSource(strings = {"P2", "AFTER_APPROVAL", "COMMIT", "BAD_UUID", "BAD_ID"})
    void reevaluate_whenExecutionFails_rollsBackAndResumesSameWork(String point) {
        long id = ready();
        var original = recoveries.findByPaymentId(id).orElseThrow();
        if (point.equals("AFTER_APPROVAL")) {
            doAnswer(i -> {
                        i.callRealMethod();
                        throw new IllegalStateException("injected");
                    })
                    .when((PaymentAuthorizationService) AopTestUtils.getUltimateTargetObject(authorization))
                    .authorizeInTransaction(any());
        } else {
            doAnswer(i -> {
                        FdsEvaluationResponse response = (FdsEvaluationResponse) i.callRealMethod();
                        if (point.equals("P2")) throw new IllegalStateException("injected");
                        if (point.equals("COMMIT"))
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    throw new IllegalStateException("injected");
                                }
                            });
                        if (point.equals("BAD_UUID"))
                            return new FdsEvaluationResponse(
                                    UUID.randomUUID(),
                                    response.evaluationId(),
                                    response.result(),
                                    response.riskScore(),
                                    response.evaluatedAt(),
                                    null);
                        if (point.equals("BAD_ID"))
                            return new FdsEvaluationResponse(
                                    response.evaluationRequestId(),
                                    original.getOriginalEvaluationId(),
                                    response.result(),
                                    response.riskScore(),
                                    response.evaluatedAt(),
                                    null);
                        return response;
                    })
                    .when(requester)
                    .reevaluate(any());
        }
        assertThatThrownBy(() -> flow.reevaluate(id)).isInstanceOf(RuntimeException.class);
        var work = recoveries.findByPaymentId(id).orElseThrow();
        assertThat(work.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.REQUESTED);
        assertThat(work.getCompletedAt()).isNull();
        assertThat(evaluationCount(id)).isEqualTo(1);
        assertThat(payments.findById(id).orElseThrow().getAuthorizationRef()).isNull();
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(requests.findByPaymentId(id).orElseThrow().getEvaluationRequestId())
                .isEqualTo(original.getOriginalEvaluationRequestId());
        assertThat(requests.findByPaymentId(id).orElseThrow().getProcessingStage())
                .isEqualTo(PaymentProcessingStage.NEEDS_REVIEW);
        assertThat(businessEvents(id)).isZero();
        doCallRealMethod().when(requester).reevaluate(any());
        doCallRealMethod()
                .when((PaymentAuthorizationService) AopTestUtils.getUltimateTargetObject(authorization))
                .authorizeInTransaction(any());
        assertThat(flow.reevaluate(id).status()).isEqualTo("COMPLETED");
        var completed = recoveries.findByPaymentId(id).orElseThrow();
        assertThat(completed.getRetryId()).isEqualTo(work.getRetryId());
        assertThat(completed.getNewEvaluationRequestId()).isEqualTo(work.getNewEvaluationRequestId());
        assertThat(completed.getRequestedAt()).isEqualTo(work.getRequestedAt());
        assertThat(evaluationCount(id)).isEqualTo(2);
        assertThat(businessEvents(id)).isEqualTo(1);
    }

    // 두 직원의 동시 요청은 같은 작업으로 평가와 승인을 한 번만 실행한다.
    @Test
    void reevaluate_whenConcurrent_executesOnlyOnce() throws Exception {
        long id = ready();
        evaluationResult(FdsResult.REVIEW);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                actor.set(101L);
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    return flow.reevaluate(id);
                } finally {
                    actor.remove();
                }
            });
            var b = pool.submit(() -> {
                actor.set(202L);
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    return flow.reevaluate(id);
                } finally {
                    actor.remove();
                }
            });
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        assertThat(evaluationCount(id)).isEqualTo(2);
        assertThat(businessEvents(id)).isEqualTo(1);
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getRequestedBy())
                .isIn(101L, 202L);
        verify(requester).reevaluate(any());
        verify(authorizer).authorize(any());
    }

    // 접수 뒤 결제·평가 변경은 재평가하지 않고 수동 종료를 커밋하며 원본과 사유를 보존한다.
    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT", "EVALUATION"})
    void execute_whenStateChanged_commitsManualReviewWithoutCallingP2(String change) {
        long id = ready();
        var work = admission.admit(id);
        var before = recoveries.findByPaymentId(id).orElseThrow();
        if (change.equals("PAYMENT"))
            jdbc.update("UPDATE payments SET authorization_ref='existing-auth' WHERE payment_id=?", id);
        else jdbc.update("UPDATE payment_requests SET evaluation_error_code='CHANGED' WHERE payment_id=?", id);
        assertThatThrownBy(() -> flow.reevaluate(id))
                .isInstanceOfSatisfying(
                        PaymentFdsReevaluationNotAllowedException.class,
                        e -> assertThat(e.getReason())
                                .isEqualTo(PaymentFdsReevaluationNotAllowedException.Reason.PAYMENT_STATE_CHANGED));
        var after = recoveries.findByPaymentId(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.MANUAL_REVIEW);
        assertThat(after.getCompletedAt()).isNotNull();
        assertThat(after.getRetryId()).isEqualTo(work.retryId());
        assertThat(after.getReasonCode()).isEqualTo(before.getReasonCode());
        assertThatThrownBy(() -> flow.reevaluate(id)).isInstanceOf(PaymentFdsReevaluationNotAllowedException.class);
        assertThat(evaluationCount(id)).isEqualTo(1);
        verifyNoInteractions(requester, authorizer);
        if (change.equals("PAYMENT"))
            assertThat(payments.findById(id).orElseThrow().getAuthorizationRef())
                    .isEqualTo("existing-auth");
    }

    // 직접 실행도 권한과 작업 키를 확인한다.
    @Test
    void execute_whenUnauthorizedOrWrongKey_rejectsWithoutP2Call() {
        long id = ready();
        var work = admission.admit(id);
        assertThatThrownBy(() -> execution.execute(id, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        when(staff.findCurrent()).thenReturn(Optional.of(new StaffSummary(202L, "관리자", "ADMIN", null, List.of())));
        assertThatThrownBy(() -> execution.execute(id, work.retryId())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(requester, authorizer);
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getStatus())
                .isEqualTo(PaymentFdsRecoveryStatus.REQUESTED);
    }
    // HTTP 진입부터 실제 P2·승인·저장을 연결하며 재요청해도 새 작업을 만들지 않는다.
    @Test
    void http_reevaluatesAndReplaysPersistedResult() throws Exception {
        long id = ready();
        var principal = com.donworry.identity.security.SessionUser.builder()
                .userId(101L)
                .role(com.donworry.identity.entity.UserRole.SECURITY_STAFF)
                .permissions(List.of())
                .build();
        var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of());
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/payment-operations/" + id + "/fds-reevaluations")
                            .with(authentication(auth))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.evaluationResult").value("ALLOW"));
        assertThat(evaluationCount(id)).isEqualTo(2);
        assertThat(businessEvents(id)).isEqualTo(1);
        verify(requester).reevaluate(any());
        verify(authorizer).authorize(any());
    }

    private org.springframework.test.web.servlet.ResultActions httpPost(long id) throws Exception {
        var principal = com.donworry.identity.security.SessionUser.builder()
                .userId(101L)
                .role(com.donworry.identity.entity.UserRole.SECURITY_STAFF)
                .permissions(List.of())
                .build();
        var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of());
        return mvc.perform(post("/api/payment-operations/" + id + "/fds-reevaluations")
                .with(authentication(auth))
                .with(csrf()));
    }

    private void assertResumable(long id, PaymentFdsReevaluationAdmission work) throws Exception {
        var row = recoveries.findByPaymentId(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.REQUESTED);
        assertThat(row.getRetryId()).isEqualTo(work.retryId());
        assertThat(row.getNewEvaluationRequestId()).isEqualTo(work.newEvaluationRequestId());
        assertThat(row.getRequestedBy()).isEqualTo(work.requestedBy());
        assertThat(row.getRequestedAt()).isEqualTo(work.requestedAt());
        assertThat(evaluationCount(id)).isEqualTo(1);
        assertThat(businessEvents(id)).isZero();
        assertThat(payments.findById(id).orElseThrow().getAuthorizationRef()).isNull();
        assertThat(requests.findByPaymentId(id).orElseThrow().getProcessingStage())
                .isEqualTo(PaymentProcessingStage.NEEDS_REVIEW);
        doCallRealMethod().when(requester).reevaluate(any());
        httpPost(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(evaluationCount(id)).isEqualTo(2);
        assertThat(businessEvents(id)).isEqualTo(1);
        assertThat(recoveries.findByPaymentId(id).orElseThrow().getRetryId()).isEqualTo(work.retryId());
    }

    // 실제 P2 원본 평가 잠금을 유지하고 실행 제한으로 중단한 뒤 같은 작업을 재개한다.
    @Test
    void http_whenP2RowLocked_timesOutAndResumes() throws Exception {
        long id = ready();
        var work = admission.admit(id);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForList("SELECT evaluation_id FROM fds_evaluations WHERE payment_id=? FOR UPDATE", id);
                locked.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                httpPost(id)
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("FDS_REEVALUATION_UNAVAILABLE"))
                        .andExpect(header().string("Cache-Control", "no-store"));
                assertThat(Duration.ofNanos(System.nanoTime() - start))
                        .isBetween(Duration.ofSeconds(3), Duration.ofSeconds(12));
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertResumable(id, work);
    }

    // 실제 잠금 순환을 만들어 MariaDB가 실행 트랜잭션을 희생시키는지 검사한다.
    @Test
    void http_whenDatabaseDeadlocks_returns503AndResumes() throws Exception {
        long id = ready();
        var work = admission.admit(id);
        var locked = new CountDownLatch(1);
        var executing = new CountDownLatch(1);
        doAnswer(i -> {
                    executing.countDown();
                    return i.callRealMethod();
                })
                .when(requester)
                .reevaluate(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                tx.setRollbackOnly();
                // 되돌릴 행 수를 늘려 반대편 fixture가 먼저 희생되지 않게 한다. 모두 롤백한다.
                for (int i = 0; i < 20; i++)
                    jdbc.update(
                            "INSERT INTO fds_evaluations (evaluation_request_id,payment_id,merchant_id,customer_ref,payment_requested_at,requested_amount,result,evaluated_at,error_code) SELECT UUID(),payment_id,merchant_id,customer_ref,payment_requested_at,requested_amount,result,evaluated_at,error_code FROM fds_evaluations WHERE payment_id=? LIMIT 1",
                            id);
                // 테스트에서만 역순 잠금. 더 많은 행을 잠가 운영 실행 쪽을 데드락 희생자로 만든다.
                jdbc.queryForList(
                        "SELECT evaluation_id FROM fds_evaluations WHERE evaluation_id <= (SELECT MAX(evaluation_id) FROM fds_evaluations) FOR UPDATE");
                jdbc.update("UPDATE fds_evaluations SET error_code='LOCK_FIXTURE' WHERE payment_id=?", id);
                locked.countDown();
                try {
                    if (!executing.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                jdbc.queryForList("SELECT payment_id FROM payments WHERE payment_id=? FOR UPDATE", id);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var result = httpPost(id)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("FDS_REEVALUATION_UNAVAILABLE"))
                    .andReturn();
            Throwable cause = result.getResolvedException();
            boolean deadlock = false;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql && sql.getErrorCode() == 1213) deadlock = true;
                cause = cause.getCause();
            }
            assertThat(deadlock).as("실제 MariaDB deadlock 오류 1213").isTrue();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            executing.countDown();
        }
        assertResumable(id, work);
    }
    // 각각 짧은 DB 작업도 실행 트랜잭션의 남은 시간을 공유하며 평가 저장까지 롤백한다.
    @Test
    void http_whenExecutionBudgetExhausted_rollsBackP2AndResumes() throws Exception {
        long id = ready();
        var work = admission.admit(id);
        doAnswer(i -> {
                    jdbc.queryForObject("SELECT SLEEP(3)", Integer.class);
                    var response = i.callRealMethod();
                    jdbc.queryForObject("SELECT SLEEP(3)", Integer.class);
                    return response;
                })
                .when(requester)
                .reevaluate(any());
        long start = System.nanoTime();
        httpPost(id)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FDS_REEVALUATION_UNAVAILABLE"));
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isBetween(Duration.ofSeconds(3), Duration.ofSeconds(10));
        assertResumable(id, work);
    }
}
