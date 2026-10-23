package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentAuthorizationPersistenceTests {
    // 승인 트랜잭션 자체를 검증한다. 실제 매입·완료 연결은 별도 통합 테스트가 담당한다.
    @MockitoBean
    com.donworry.settlement.service.CaptureService captureService;

    @Autowired
    PaymentEvaluationFlowService flow;

    @Autowired
    PaymentAuthorizationService authorization;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    PaymentAuthorizer authorizer;

    private PaymentEvaluationFlowInput input(String customer, long amount) {
        return new PaymentEvaluationFlowInput(999L, customer, UUID.randomUUID().toString(), amount);
    }

    private PaymentEvaluationFlowInput input() {
        return input(UUID.randomUUID().toString(), 150_000L);
    }

    private AuthorizationResult success(AuthorizationCommand command) {
        return new MockPaymentAuthorizer().authorize(command);
    }

    private PaymentAuthorizationContext evaluatedContext() {
        var evaluated = flow.createAndEvaluate(input());
        var snapshot = PaymentCreationSnapshot.from(
                payments.findById(evaluated.paymentId()).orElseThrow());
        return new PaymentAuthorizationContext(
                snapshot,
                evaluated.evaluationResult().evaluation().evaluationRequestId(),
                evaluated.evaluationResult());
    }

    private void assertStored(long id, PaymentStatus status, String reference) {
        var stored = payments.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getAuthorizationRef()).isEqualTo(reference);
    }

    private void assertEvaluationPreserved(long id) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fds_evaluations WHERE payment_id = ?", Long.class, id))
                .isEqualTo(1L);
    }

    // 실제 P2의 ALLOW·REVIEW에 따라 상태와 참조가 커밋되고 호출자 롤백에도 유지된다.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createAndAuthorize_withRealEvaluation_commitsIndependently(boolean review) {
        String customer = UUID.randomUUID().toString();
        if (review) {
            flow.createAndEvaluate(input(customer, 50_000L));
            flow.createAndEvaluate(input(customer, 70_000L));
        }
        when(authorizer.authorize(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            return success(invocation.getArgument(0));
        });
        var result = new TransactionTemplate(transactionManager).execute(status -> {
            var authorized = flow.createAndAuthorize(input(customer, 150_000L));
            status.setRollbackOnly();
            return authorized;
        });
        assertThat(result.evaluationResult().evaluation().result())
                .isEqualTo(review ? FdsResult.REVIEW : FdsResult.ALLOW);
        assertStored(
                result.paymentId(),
                review ? PaymentStatus.HELD : PaymentStatus.PENDING,
                "MOCK-AUTH-" + result.paymentId());
        assertEvaluationPreserved(result.paymentId());
        verify(authorizer).authorize(new AuthorizationCommand(result.paymentId(), 150_000L));
    }

    // DENY·ERROR 문맥 대역으로 실제 DB 반영을 확인하되 승인기는 호출하지 않는다.
    @ParameterizedTest
    @EnumSource(
            value = FdsResult.class,
            names = {"DENY", "ERROR"})
    void authorize_withNonApprovalVerdict_persistsExpectedState(FdsResult verdict) {
        var original = evaluatedContext();
        var response = new FdsEvaluationResponse(
                original.evaluationRequestId(),
                999L,
                verdict,
                verdict == FdsResult.ERROR ? null : 80,
                Instant.now(),
                verdict == FdsResult.ERROR ? "TEST_ERROR" : null);
        var context = new PaymentAuthorizationContext(
                original.payment(),
                original.evaluationRequestId(),
                new PaymentEvaluationResult(
                        response, original.evaluationResult().decision()));
        authorization.authorize(context);
        assertStored(
                context.payment().paymentId(),
                verdict == FdsResult.DENY ? PaymentStatus.DENIED : PaymentStatus.PENDING,
                null);
        verifyNoInteractions(authorizer);
    }

    // 업무 거절을 커밋한 결제는 같은 문맥을 다시 받아도 재승인하지 않는다.
    @Test
    void authorize_whenDeclined_commitsAndRejectsDuplicate() {
        var context = evaluatedContext();
        when(authorizer.authorize(any()))
                .thenReturn(new AuthorizationResult(AuthorizationResult.Type.DECLINED, null, "MOCK_DECLINED"));
        var result = authorization.authorize(context);
        assertThat(result.declineCode()).isEqualTo("MOCK_DECLINED");
        assertStored(result.paymentId(), PaymentStatus.DECLINED, null);
        assertThatThrownBy(() -> authorization.authorize(context)).isInstanceOf(IllegalStateException.class);
        verify(authorizer).authorize(any());
    }

    // flush로 SQL을 실행한 뒤 커밋 직전 실패해도 상태·참조는 함께 롤백되고 평가 기록은 남는다.
    @Test
    void createAndAuthorize_whenCommitFails_rollsBackBothFields() {
        String customer = UUID.randomUUID().toString();
        flow.createAndEvaluate(input(customer, 50_000L));
        flow.createAndEvaluate(input(customer, 70_000L));
        var paymentId = new AtomicLong();
        var failure = new IllegalStateException("injected before-commit failure");
        when(authorizer.authorize(any())).thenAnswer(invocation -> {
            AuthorizationCommand command = invocation.getArgument(0);
            paymentId.set(command.paymentId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    entityManager.flush();
                    assertThat(jdbc.queryForObject(
                                    "SELECT status FROM payments WHERE payment_id = ?",
                                    String.class,
                                    command.paymentId()))
                            .isEqualTo("HELD");
                    assertThat(jdbc.queryForObject(
                                    "SELECT authorization_ref FROM payments WHERE payment_id = ?",
                                    String.class,
                                    command.paymentId()))
                            .isEqualTo("MOCK-AUTH-" + command.paymentId());
                    throw failure;
                }
            });
            return success(command);
        });
        assertThatThrownBy(() -> flow.createAndAuthorize(input(customer, 150_000L)))
                .isSameAs(failure);
        assertStored(paymentId.get(), PaymentStatus.PENDING, null);
        assertEvaluationPreserved(paymentId.get());
        verify(authorizer).authorize(any());
    }

    // 승인기 기술 실패는 최초 결제·평가를 보존하고 확실한 롤백 후 동일 문맥으로 재처리할 수 있다.
    @Test
    void authorize_whenTechnicalFailure_preservesInitialStateAndAllowsExplicitRetry() {
        var context = evaluatedContext();
        var failure = new IllegalStateException("mock unavailable");
        when(authorizer.authorize(any())).thenThrow(failure);
        assertThatThrownBy(() -> authorization.authorize(context)).isSameAs(failure);
        assertStored(context.payment().paymentId(), PaymentStatus.PENDING, null);
        assertEvaluationPreserved(context.payment().paymentId());
        doAnswer(invocation -> success(invocation.getArgument(0)))
                .when(authorizer)
                .authorize(any());
        var result = authorization.authorize(context);
        assertStored(result.paymentId(), PaymentStatus.PENDING, result.authorizationRef());
        verify(authorizer, times(2)).authorize(any());
    }

    // 첫 트랜잭션을 승인기 안에서 멈추면 두 번째 처리는 기다리며 커밋/롤백 후 최신 상태를 판단한다.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorize_whenConcurrent_serializesUntilFirstTransactionEnds(boolean rollbackFirst) throws Exception {
        var context = evaluatedContext();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(authorizer.authorize(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("test release timed out");
                if (rollbackFirst) throw new IllegalStateException("first rollback");
            }
            return success(invocation.getArgument(0));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> authorization.authorize(context));
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return authorization.authorize(context);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(calls.get()).isEqualTo(1);
            releaseFirst.countDown();
            if (rollbackFirst) {
                assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(second.get(10, TimeUnit.SECONDS).authorizationRef()).isNotNull();
                assertThat(calls.get()).isEqualTo(2);
            } else {
                assertThat(first.get(10, TimeUnit.SECONDS).authorizationRef()).isNotNull();
                assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(calls.get()).isEqualTo(1);
            }
            assertStored(
                    context.payment().paymentId(),
                    PaymentStatus.PENDING,
                    "MOCK-AUTH-" + context.payment().paymentId());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
