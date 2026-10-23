package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class PaymentEvaluationFlowServiceTests {
    private final PaymentCreationService creation = mock(PaymentCreationService.class);
    private final FdsEvaluator evaluator = mock(FdsEvaluator.class);
    private final PaymentAuthorizationService authorization = mock(PaymentAuthorizationService.class);
    private final PaymentEvaluationFlowService service =
            new PaymentEvaluationFlowService(creation, new PaymentEvaluationService(evaluator), authorization);

    private PaymentEvaluationFlowInput input() {
        return new PaymentEvaluationFlowInput(999L, "input-customer", "order-1", 10_000L);
    }

    private PaymentCreationSnapshot snapshot() {
        return PaymentCreationSnapshot.builder()
                .paymentId(101L)
                .customerRef("stored-customer")
                .amount(150_000L)
                .createdAt(PaymentEvaluationFixtures.REQUESTED_AT)
                .status(PaymentStatus.PENDING)
                .build();
    }

    // 최초 저장 뒤 입력 원본 대신 저장값으로 한 번 평가하고 각 판정의 다음 단계를 반환한다
    @ParameterizedTest
    @EnumSource(FdsResult.class)
    void createAndEvaluate_withResult_usesStoredValuesAndReturnsDecision(FdsResult verdict) {
        var saved = snapshot();
        when(creation.createPendingSnapshot(999L, "input-customer", "order-1", 10_000L))
                .thenReturn(saved);
        when(evaluator.evaluate(any())).thenAnswer(invocation -> {
            FdsEvaluationRequest request = invocation.getArgument(0);
            return new FdsEvaluationResponse(
                    request.evaluationRequestId(),
                    201L,
                    verdict,
                    verdict == FdsResult.ERROR ? null : 60,
                    saved.createdAt().plusSeconds(1),
                    verdict == FdsResult.ERROR ? "FDS_FAILED" : null);
        });

        var result = service.createAndEvaluate(input());

        var order = inOrder(creation, evaluator);
        order.verify(creation).createPendingSnapshot(999L, "input-customer", "order-1", 10_000L);
        var captor = ArgumentCaptor.forClass(FdsEvaluationRequest.class);
        order.verify(evaluator).evaluate(captor.capture());
        verifyNoMoreInteractions(creation, evaluator);
        verifyNoInteractions(authorization);
        var request = captor.getValue();
        assertThat(request.evaluationRequestId()).isNotNull();
        assertThat(request.paymentId()).isEqualTo(saved.paymentId());
        assertThat(request.customerRef()).isEqualTo(saved.customerRef());
        assertThat(request.amount()).isEqualTo(saved.amount());
        assertThat(request.paymentRequestedAt()).isEqualTo(saved.createdAt());
        assertThat(result.paymentId()).isEqualTo(saved.paymentId());
        assertThat(result.evaluationResult().evaluation().result()).isEqualTo(verdict);
        assertThat(result.evaluationResult().evaluation().evaluationRequestId())
                .isEqualTo(request.evaluationRequestId());
        var decision = result.evaluationResult().decision();
        assertThat(decision.state().status())
                .isEqualTo(verdict == FdsResult.DENY ? PaymentStatus.DENIED : PaymentStatus.PENDING);
        assertThat(decision.nextStep())
                .isEqualTo(
                        switch (verdict) {
                            case ALLOW, REVIEW -> PaymentRules.NextStep.REQUEST_AUTHORIZATION;
                            case DENY -> PaymentRules.NextStep.NONE;
                            case ERROR -> PaymentRules.NextStep.RECORD_FDS_ERROR;
                        });
    }

    // 저장 실패 시 평가를 시작하지 않는다
    @Test
    void createAndEvaluate_whenCreationFails_doesNotEvaluate() {
        var failure = new IllegalStateException("save failed");
        when(creation.createPendingSnapshot(any(), any(), any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> service.createAndEvaluate(input())).isSameAs(failure);
        verifyNoInteractions(evaluator);
    }

    // 평가 호출 실패를 재시도하지 않고 그대로 전달한다
    @Test
    void createAndEvaluate_whenEvaluationFails_propagatesWithoutRetry() {
        when(creation.createPendingSnapshot(any(), any(), any(), any())).thenReturn(snapshot());
        var failure = new IllegalStateException("evaluation failed");
        when(evaluator.evaluate(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.createAndEvaluate(input())).isSameAs(failure);
        verify(creation).createPendingSnapshot(any(), any(), any(), any());
        verify(evaluator).evaluate(any());
        verifyNoMoreInteractions(creation, evaluator);
    }

    // 입력 누락 시 저장과 평가를 시작하지 않는다
    @Test
    void createAndEvaluate_withNullInput_rejectsBeforeSaving() {
        assertThatThrownBy(() -> service.createAndEvaluate(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(creation, evaluator);
    }
}
