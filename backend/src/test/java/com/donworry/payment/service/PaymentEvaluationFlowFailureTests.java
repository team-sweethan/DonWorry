package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentEvaluationFlowFailureTests {
    @Autowired
    PaymentEvaluationFlowService service;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    FdsEvaluator evaluator;

    private PaymentEvaluationFlowInput input() {
        return new PaymentEvaluationFlowInput(
                999L, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 150_000L);
    }

    // 평가 전에 별도 트랜잭션에서 최초 결제를 조회할 수 있고 평가 실패와 호출자 롤백 후에도 남는다
    @Test
    void createAndEvaluate_whenEvaluatorThrows_preservesCommittedPayment() {
        var captured = new java.util.concurrent.atomic.AtomicReference<FdsEvaluationRequest>();
        var failure = new IllegalStateException("injected evaluator failure");
        when(evaluator.evaluate(any())).thenAnswer(invocation -> {
            FdsEvaluationRequest request = invocation.getArgument(0);
            captured.set(request);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            var independent = new TransactionTemplate(transactionManager);
            independent.executeWithoutResult(
                    status -> assertThat(payments.findById(request.paymentId())).isPresent());
            throw failure;
        });
        var caller = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> caller.execute(status -> service.createAndEvaluate(input())))
                .isSameAs(failure);
        var request = captured.get();
        assertThat(request).isNotNull();
        var saved = payments.findById(request.paymentId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getAuthorizationRef()).isNull();
        assertThat(saved.getCustomerRef()).isEqualTo(request.customerRef());
        assertThat(saved.getAmount()).isEqualTo(request.amount());
        assertThat(saved.getCreatedAt()).isEqualTo(request.paymentRequestedAt());
        verify(evaluator).evaluate(any());
        verifyNoMoreInteractions(evaluator);
    }

    // 대역의 DENY·ERROR 응답은 판단으로 반환하며 DB 결제는 승인 전 PENDING으로 유지한다
    @ParameterizedTest
    @EnumSource(
            value = FdsResult.class,
            names = {"DENY", "ERROR"})
    void createAndEvaluate_withDenyOrError_keepsStoredPaymentPending(FdsResult verdict) {
        when(evaluator.evaluate(any())).thenAnswer(invocation -> {
            FdsEvaluationRequest request = invocation.getArgument(0);
            return new FdsEvaluationResponse(
                    request.evaluationRequestId(),
                    201L,
                    verdict,
                    verdict == FdsResult.ERROR ? null : 80,
                    Instant.now(),
                    verdict == FdsResult.ERROR ? "INJECTED_ERROR" : null);
        });
        var result = service.createAndEvaluate(input());
        var saved = payments.findById(result.paymentId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getAuthorizationRef()).isNull();
        assertThat(result.evaluationResult().evaluation().result()).isEqualTo(verdict);
        assertThat(result.evaluationResult().decision().state().status())
                .isEqualTo(verdict == FdsResult.DENY ? PaymentStatus.DENIED : PaymentStatus.PENDING);
        assertThat(result.evaluationResult().decision().nextStep())
                .isEqualTo(
                        verdict == FdsResult.DENY
                                ? PaymentRules.NextStep.NONE
                                : PaymentRules.NextStep.RECORD_FDS_ERROR);
        verify(evaluator).evaluate(any());
        verifyNoMoreInteractions(evaluator);
    }
}
