package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRequestFlowMariaDbTests {
    @Autowired
    PaymentRequestFlowService flow;

    @Autowired
    PaymentRequestAdmissionService admission;

    @Autowired
    PaymentRequestProcessingService processing;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    PaymentRepository payments;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    PaymentFdsRecoveryRepository recoveries;

    @MockitoBean
    FdsFailureReporter failureReporter;

    @MockitoBean
    FdsEvaluator evaluator;

    @MockitoBean
    PaymentAuthorizer authorizer;

    private PaymentRequestCommand command() {
        return new PaymentRequestCommand(
                999L,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1000L,
                Instant.now().minusSeconds(1),
                UUID.randomUUID());
    }

    private FdsEvaluationResponse response(FdsEvaluationRequest r, FdsResult result) {
        return new FdsEvaluationResponse(
                r.evaluationRequestId(),
                123L,
                result,
                result == FdsResult.ERROR ? null : 50,
                Instant.parse("2026-10-25T00:00:00Z"),
                result == FdsResult.ERROR ? "TIMEOUT" : null);
    }

    private void evaluation(FdsResult result) {
        doAnswer(i -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return response(i.getArgument(0), result);
                })
                .when(evaluator)
                .evaluate(any());
        doAnswer(i -> new MockPaymentAuthorizer().authorize(i.getArgument(0)))
                .when(authorizer)
                .authorize(any());
    }

    private PaymentProcessingStage stage(long id) {
        return requests.findByPaymentId(id).orElseThrow().getProcessingStage();
    }

    // 성공·보류·거절·오류를 저장하고 동일 요청에서 평가와 승인을 중복 실행하지 않는다.
    @ParameterizedTest
    @EnumSource(FdsResult.class)
    void submit_whenEvaluationReturns_persistsOutcomeAndReplays(FdsResult result) {
        evaluation(result);
        var c = command();
        var first = flow.submit(c);
        var replay = flow.submit(c);
        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(replay.created()).isFalse();
        assertThat(stage(first.paymentId()))
                .isEqualTo(
                        result == FdsResult.ERROR
                                ? PaymentProcessingStage.NEEDS_REVIEW
                                : PaymentProcessingStage.AUTHORIZED);
        var expected = switch (result) {
            case ALLOW -> PaymentStatus.CAPTURED;
            case REVIEW -> PaymentStatus.HELD;
            case DENY -> PaymentStatus.DENIED;
            case ERROR -> PaymentStatus.PENDING;
        };
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payments.findById(first.paymentId())
                                .orElseThrow()
                                .getStatus())
                        .isEqualTo(expected));
        verify(evaluator).evaluate(any());
        assertThat(recoveries.findByPaymentId(first.paymentId()).isPresent()).isEqualTo(result == FdsResult.ERROR);
        verify(failureReporter, times(result == FdsResult.ERROR ? 1 : 0)).report(any());
        verify(authorizer, times(result == FdsResult.ALLOW || result == FdsResult.REVIEW ? 1 : 0))
                .authorize(any());
    }

    // 카드 승인 거절도 완료한 처리 단계로 저장해 같은 키로 다시 승인하지 않는다.
    @Test
    void submit_whenCardDeclines_preservesDeclineWithoutReauthorization() {
        evaluation(FdsResult.ALLOW);
        doReturn(new AuthorizationResult(AuthorizationResult.Type.DECLINED, null, "CARD_DECLINED"))
                .when(authorizer)
                .authorize(any());
        var c = command();
        var first = flow.submit(c);
        flow.submit(c);
        assertThat(stage(first.paymentId())).isEqualTo(PaymentProcessingStage.AUTHORIZED);
        assertThat(payments.findById(first.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.DECLINED);
        verify(authorizer).authorize(any());
        verify(evaluator).evaluate(any());
    }

    // 평가 응답 전에 통신이 실패하면 접수는 유지하고 같은 UUID·입력으로 다시 호출한다.
    @Test
    void submit_whenEvaluationFails_retriesWithSameSavedInput() {
        evaluation(FdsResult.REVIEW);
        doThrow(new IllegalStateException("connection failed"))
                .doAnswer(i -> response(i.getArgument(0), FdsResult.REVIEW))
                .when(evaluator)
                .evaluate(any());
        var c = command();
        assertThatThrownBy(() -> flow.submit(c)).hasMessage("connection failed");
        var saved = requests.findByMerchantIdAndIdempotencyKey(c.merchantId(), c.idempotencyKey())
                .orElseThrow();
        assertThat(saved.getProcessingStage()).isEqualTo(PaymentProcessingStage.RECEIVED);
        assertThat(recoveries.findByPaymentId(saved.getPaymentId())).isEmpty();
        verifyNoInteractions(failureReporter);
        var result = flow.submit(c);
        assertThat(result.paymentId()).isEqualTo(saved.getPaymentId());
        var inputs = org.mockito.ArgumentCaptor.forClass(FdsEvaluationRequest.class);
        verify(evaluator, times(2)).evaluate(inputs.capture());
        assertThat(inputs.getAllValues().get(0)).isEqualTo(inputs.getAllValues().get(1));
        assertThat(inputs.getValue().evaluationRequestId()).isEqualTo(saved.getEvaluationRequestId());
        assertThat(inputs.getValue().merchantId())
                .isEqualTo(payments.findById(saved.getPaymentId()).orElseThrow().getMerchantId())
                .isEqualTo(c.merchantId());
    }

    // 평가 저장 이후 중단되면 P2를 다시 부르지 않고 저장 결과로 승인한다.
    @Test
    void submit_afterEvaluationCommit_resumesWithoutCallingP2() {
        evaluation(FdsResult.REVIEW);
        var c = command();
        var admitted = admission.admit(c);
        processing.saveEvaluation(
                admitted.paymentId(),
                new PaymentObservedEvaluation(
                        new FdsEvaluationResponse(
                                admitted.evaluationRequestId(), 123L, FdsResult.REVIEW, 50, Instant.now(), null),
                        Instant.now(),
                        Instant.now()));
        var result = flow.submit(c);
        assertThat(stage(result.paymentId())).isEqualTo(PaymentProcessingStage.AUTHORIZED);
        verifyNoInteractions(evaluator);
        verify(authorizer).authorize(any());
    }

    // 승인 커밋 실패는 상태·요청 단계·이벤트를 롤백하고 저장 평가로 재개한다.
    @Test
    void submit_whenAuthorizationCommitFails_rollsBackAndResumes() {
        evaluation(FdsResult.ALLOW);
        doAnswer(i -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("commit failed");
                        }
                    });
                    return new MockPaymentAuthorizer().authorize(i.getArgument(0));
                })
                .when(authorizer)
                .authorize(any());
        var c = command();
        assertThatThrownBy(() -> flow.submit(c)).hasMessage("commit failed");
        var saved = requests.findByMerchantIdAndIdempotencyKey(c.merchantId(), c.idempotencyKey())
                .orElseThrow();
        assertThat(stage(saved.getPaymentId())).isEqualTo(PaymentProcessingStage.EVALUATED);
        var payment = payments.findById(saved.getPaymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAuthorizationRef()).isNull();
        doAnswer(i -> new MockPaymentAuthorizer().authorize(i.getArgument(0)))
                .when(authorizer)
                .authorize(any());
        flow.submit(c);
        assertThat(stage(saved.getPaymentId())).isEqualTo(PaymentProcessingStage.AUTHORIZED);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payments.findById(saved.getPaymentId())
                                .orElseThrow()
                                .getStatus())
                        .isEqualTo(PaymentStatus.CAPTURED));
        verify(evaluator).evaluate(any());
    }

    // 동시에 받은 같은 평가 응답은 보존하되 승인과 HELD 전이는 한 번만 실행한다.
    @Test
    void submit_whenConcurrentSameRequest_authorizesOnce() throws Exception {
        evaluation(FdsResult.REVIEW);
        var barrier = new CyclicBarrier(2);
        doAnswer(i -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return response(i.getArgument(0), FdsResult.REVIEW);
                })
                .when(evaluator)
                .evaluate(any());
        var c = command();
        admission.admit(c);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> flow.submit(c));
            var b = pool.submit(() -> flow.submit(c));
            assertThat(a.get(20, TimeUnit.SECONDS).paymentId())
                    .isEqualTo(b.get(20, TimeUnit.SECONDS).paymentId());
        }
        verify(authorizer).authorize(any());
        verify(evaluator, times(2)).evaluate(any());
    }

    // 요청 UUID가 다른 응답은 저장하거나 승인하지 않는다.
    @Test
    void submit_whenResponseIdDiffers_keepsReceivedStage() {
        evaluation(FdsResult.ALLOW);
        doReturn(new FdsEvaluationResponse(UUID.randomUUID(), 123L, FdsResult.ALLOW, 10, Instant.now(), null))
                .when(evaluator)
                .evaluate(any());
        var c = command();
        assertThatThrownBy(() -> flow.submit(c)).isInstanceOf(IllegalStateException.class);
        var saved = requests.findByMerchantIdAndIdempotencyKey(c.merchantId(), c.idempotencyKey())
                .orElseThrow();
        assertThat(saved.getProcessingStage()).isEqualTo(PaymentProcessingStage.RECEIVED);
        verifyNoInteractions(authorizer);
    }
    // 보고 시점에는 ERROR와 복구 행이 이미 커밋되어 있고 보고 실패·재요청에도 원본 키를 보존한다.
    @Test
    void submit_whenFailureReportThrows_preservesCommittedErrorAndDoesNotReportOnReplay() {
        evaluation(FdsResult.ERROR);
        var c = command();
        doAnswer(invocation -> {
                    FdsEvaluationFailureReport report = invocation.getArgument(0);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    var recovery =
                            recoveries.findByPaymentId(report.paymentId()).orElseThrow();
                    assertThat(stage(report.paymentId())).isEqualTo(PaymentProcessingStage.NEEDS_REVIEW);
                    assertThat(report.failureReportId()).isEqualTo(recovery.getFailureReportId());
                    assertThat(report.originalEvaluationRequestId()).isEqualTo(report.evaluationRequestId());
                    assertThat(report.evaluationAttemptNo()).isEqualTo(1);
                    assertThat(report.requestedAt()).isEqualTo(recovery.getEvaluationRequestedAt());
                    assertThat(report.respondedAt()).isEqualTo(recovery.getEvaluationRespondedAt());
                    assertThat(report.sanitizedErrorMessage()).isNull();
                    assertThat(report.deviceId()).isNull();
                    throw new IllegalStateException("sensitive internal message");
                })
                .when(failureReporter)
                .report(any());
        var first = flow.submit(c);
        var saved = recoveries.findByPaymentId(first.paymentId()).orElseThrow();
        flow.submit(c);
        var replay = recoveries.findByPaymentId(first.paymentId()).orElseThrow();
        assertThat(replay.getFailureReportId()).isEqualTo(saved.getFailureReportId());
        assertThat(replay.getEvaluationRequestedAt()).isEqualTo(saved.getEvaluationRequestedAt());
        assertThat(replay.getStatus()).isEqualTo(PaymentFdsRecoveryStatus.WAITING_ANALYSIS);
        verify(failureReporter).report(any());
        verifyNoInteractions(authorizer);
    }

    // 복구 행 저장 실패는 ERROR 평가 저장도 롤백하고 보고를 실행하지 않는다.
    @Test
    void submit_whenRecoverySaveFails_rollsBackErrorAndSkipsReport() {
        evaluation(FdsResult.ERROR);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("storage failure"))
                .when(recoveries)
                .save(any(PaymentFdsRecovery.class));
        var c = command();
        assertThatThrownBy(() -> flow.submit(c))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var saved = requests.findByMerchantIdAndIdempotencyKey(c.merchantId(), c.idempotencyKey())
                .orElseThrow();
        assertThat(saved.getProcessingStage()).isEqualTo(PaymentProcessingStage.RECEIVED);
        assertThat(saved.getEvaluationId()).isNull();
        assertThat(recoveries.findByPaymentId(saved.getPaymentId())).isEmpty();
        verifyNoInteractions(failureReporter, authorizer);
    }

    // 동시에 받은 같은 ERROR는 보고 한 건과 최초 관측값 한 쌍만 저장한다.
    @Test
    void submit_whenConcurrentError_storesAndReportsOnce() throws Exception {
        evaluation(FdsResult.ERROR);
        var barrier = new CyclicBarrier(2);
        doAnswer(i -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return response(i.getArgument(0), FdsResult.ERROR);
                })
                .when(evaluator)
                .evaluate(any());
        var c = command();
        admission.admit(c);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> flow.submit(c));
            var b = pool.submit(() -> flow.submit(c));
            var id = a.get(20, TimeUnit.SECONDS).paymentId();
            assertThat(b.get(20, TimeUnit.SECONDS).paymentId()).isEqualTo(id);
            assertThat(recoveries.findByPaymentId(id)).isPresent();
        }
        verify(failureReporter).report(any());
        verifyNoInteractions(authorizer);
    }
}
