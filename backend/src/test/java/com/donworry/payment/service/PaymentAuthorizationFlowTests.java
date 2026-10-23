package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentAuthorizationFlowTests {
    private final PaymentCreationService creation = mock(PaymentCreationService.class);
    private final FdsEvaluator evaluator = mock(FdsEvaluator.class);
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentAuthorizer authorizer = mock(PaymentAuthorizer.class);
    private final PaymentEvaluationFlowService service = new PaymentEvaluationFlowService(
            creation, new PaymentEvaluationService(evaluator), new PaymentAuthorizationService(repository, authorizer));
    private final PaymentEvaluationFlowInput input =
            new PaymentEvaluationFlowInput(1L, "input-customer", "order", 1000L);
    private final Payment payment =
            Payment.pending(1L, "stored-customer", "order", 2000L, PaymentEvaluationFixtures.REQUESTED_AT);

    private void prepare(FdsResult verdict) {
        ReflectionTestUtils.setField(payment, "paymentId", 101L);
        when(creation.createPendingSnapshot(any(), any(), any(), any()))
                .thenReturn(PaymentCreationSnapshot.from(payment));
        when(repository.findByIdForUpdate(101L)).thenReturn(Optional.of(payment));
        when(evaluator.evaluate(any())).thenAnswer(invocation -> {
            FdsEvaluationRequest request = invocation.getArgument(0);
            return response(request.evaluationRequestId(), verdict);
        });
    }

    private FdsEvaluationResponse response(UUID id, FdsResult verdict) {
        return new FdsEvaluationResponse(
                id,
                201L,
                verdict,
                verdict == FdsResult.ERROR ? null : 50,
                PaymentEvaluationFixtures.REQUESTED_AT.plusSeconds(1),
                verdict == FdsResult.ERROR ? "FDS_ERROR" : null);
    }

    // 저장된 결제로 평가한 동일 문맥이 승인 단계까지 이어지고 각 분기의 최종 결과를 반환한다.
    @ParameterizedTest
    @EnumSource(FdsResult.class)
    void createAndAuthorize_withEvaluation_connectsStoredPaymentToAuthorization(FdsResult verdict) {
        prepare(verdict);
        when(authorizer.authorize(any()))
                .thenAnswer(invocation -> new MockPaymentAuthorizer().authorize(invocation.getArgument(0)));
        var result = service.createAndAuthorize(input);
        var order = inOrder(creation, evaluator, repository, authorizer);
        order.verify(creation).createPendingSnapshot(1L, "input-customer", "order", 1000L);
        var request = ArgumentCaptor.forClass(FdsEvaluationRequest.class);
        order.verify(evaluator).evaluate(request.capture());
        order.verify(repository).findByIdForUpdate(101L);
        assertThat(request.getValue().paymentId()).isEqualTo(101L);
        assertThat(request.getValue().customerRef()).isEqualTo("stored-customer");
        assertThat(request.getValue().amount()).isEqualTo(2000L);
        assertThat(request.getValue().paymentRequestedAt()).isEqualTo(payment.getCreatedAt());
        assertThat(result.evaluationResult().evaluation().evaluationRequestId())
                .isEqualTo(request.getValue().evaluationRequestId());
        assertThat(result.paymentId()).isEqualTo(101L);
        assertThat(result.status())
                .isEqualTo(
                        switch (verdict) {
                            case ALLOW, ERROR -> PaymentStatus.PENDING;
                            case REVIEW -> PaymentStatus.HELD;
                            case DENY -> PaymentStatus.DENIED;
                        });
        assertThat(result.nextStep())
                .isEqualTo(
                        switch (verdict) {
                            case ALLOW -> PaymentRules.NextStep.REQUEST_CAPTURE;
                            case REVIEW -> PaymentRules.NextStep.NOTIFY_HELD;
                            case DENY -> PaymentRules.NextStep.NONE;
                            case ERROR -> PaymentRules.NextStep.RECORD_FDS_ERROR;
                        });
        if (verdict == FdsResult.ALLOW || verdict == FdsResult.REVIEW) {
            order.verify(authorizer).authorize(new AuthorizationCommand(101L, 2000L));
            assertThat(result.authorizationRef()).isEqualTo("MOCK-AUTH-101");
        } else {
            verifyNoInteractions(authorizer);
            assertThat(result.authorizationRef()).isNull();
        }
        order.verifyNoMoreInteractions();
    }

    // 평가 예외·빈 응답·요청 ID 불일치는 잠금 조회와 승인 단계에 진입하지 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"exception", "null", "mismatch"})
    void createAndAuthorize_whenEvaluationInvalid_stopsBeforeAuthorization(String failure) {
        prepare(FdsResult.ALLOW);
        switch (failure) {
            case "exception" ->
                doThrow(new IllegalStateException("FDS unavailable"))
                        .when(evaluator)
                        .evaluate(any());
            case "null" -> doReturn(null).when(evaluator).evaluate(any());
            default ->
                doReturn(response(UUID.randomUUID(), FdsResult.ALLOW))
                        .when(evaluator)
                        .evaluate(any());
        }
        assertThatThrownBy(() -> service.createAndAuthorize(input)).isInstanceOf(IllegalStateException.class);
        verify(evaluator).evaluate(any());
        verifyNoMoreInteractions(evaluator);
        verifyNoInteractions(repository, authorizer);
    }

    // 업무 거절은 DECLINED로 반환하지만 모의 기술 실패는 원래 예외를 전달한다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createAndAuthorize_whenAuthorizationNotSuccessful_distinguishesDeclineAndFailure(boolean declined) {
        prepare(FdsResult.REVIEW);
        var failure = new IllegalStateException("mock unavailable");
        if (declined) {
            when(authorizer.authorize(any()))
                    .thenReturn(new AuthorizationResult(AuthorizationResult.Type.DECLINED, null, "MOCK_DECLINED"));
            var result = service.createAndAuthorize(input);
            assertThat(result.status()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(result.authorizationRef()).isNull();
            assertThat(result.declineCode()).isEqualTo("MOCK_DECLINED");
        } else {
            when(authorizer.authorize(any())).thenThrow(failure);
            assertThatThrownBy(() -> service.createAndAuthorize(input)).isSameAs(failure);
        }
        verify(authorizer).authorize(new AuthorizationCommand(101L, 2000L));
        verifyNoMoreInteractions(authorizer);
    }

    // 최초 저장 실패는 후속 평가와 승인을 시작하지 않는다.
    @Test
    void createAndAuthorize_whenCreationFails_stopsBeforeEvaluation() {
        var failure = new IllegalStateException("save failed");
        when(creation.createPendingSnapshot(any(), any(), any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> service.createAndAuthorize(input)).isSameAs(failure);
        verifyNoInteractions(evaluator, repository, authorizer);
    }

    // 입력 누락은 저장 전에 거절한다.
    @Test
    void createAndAuthorize_whenInputNull_rejectsBeforeSaving() {
        assertThatThrownBy(() -> service.createAndAuthorize(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(creation, evaluator, repository, authorizer);
    }
}
