package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.entity.PaymentStatus;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentEvaluationDecisionTests {

    private final FdsEvaluator evaluator = mock(FdsEvaluator.class);
    private final PaymentEvaluationService service = new PaymentEvaluationService(evaluator);
    private final PaymentEvaluationInput input =
            PaymentEvaluationFixtures.validInput().build();

    // 점수로 재판정하지 않고 원래 평가 판정에 따라 상태와 다음 단계를 정한다.
    @ParameterizedTest
    @CsvSource({
        "ALLOW,PENDING,REQUEST_AUTHORIZATION",
        "REVIEW,PENDING,REQUEST_AUTHORIZATION",
        "DENY,DENIED,NONE",
        "ERROR,PENDING,RECORD_FDS_ERROR"
    })
    void evaluateAndDecide_whenValidResponse_returnsEvaluationAndDecision(
            FdsResult result, PaymentStatus status, PaymentRules.NextStep nextStep) {
        var response = response(result, input.evaluationRequestId());
        when(evaluator.evaluate(any(FdsEvaluationRequest.class))).thenReturn(response);

        var actual = service.evaluateAndDecide(PaymentRules.State.pending(), input);

        assertThat(actual.evaluation()).isSameAs(response);
        assertThat(actual.decision().state())
                .isEqualTo(new PaymentRules.State(status, PaymentRules.AuthorizationStatus.NOT_REQUESTED));
        assertThat(actual.decision().nextStep()).isEqualTo(nextStep);
        verify(evaluator)
                .evaluate(FdsEvaluationRequest.builder()
                        .evaluationRequestId(input.evaluationRequestId())
                        .paymentId(input.paymentId())
                        .customerRef(input.customerRef())
                        .amount(input.amount())
                        .paymentRequestedAt(input.paymentRequestedAt())
                        .build());
        verifyNoMoreInteractions(evaluator);
    }

    // 승인 전 PENDING이 아니면 평가 이력을 만들기 전에 거절한다.
    @ParameterizedTest
    @MethodSource("ineligibleStates")
    void evaluateAndDecide_whenStateIneligible_rejectsBeforeCallingEvaluator(PaymentRules.State state) {
        assertThatThrownBy(() -> service.evaluateAndDecide(state, input)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(evaluator);
    }

    static Stream<PaymentRules.State> ineligibleStates() {
        return Stream.of(
                new PaymentRules.State(PaymentStatus.PENDING, PaymentRules.AuthorizationStatus.SUCCEEDED),
                new PaymentRules.State(PaymentStatus.PENDING, PaymentRules.AuthorizationStatus.UNKNOWN),
                new PaymentRules.State(PaymentStatus.DENIED, PaymentRules.AuthorizationStatus.NOT_REQUESTED),
                new PaymentRules.State(PaymentStatus.DECLINED, PaymentRules.AuthorizationStatus.DECLINED),
                new PaymentRules.State(PaymentStatus.HELD, PaymentRules.AuthorizationStatus.SUCCEEDED),
                new PaymentRules.State(PaymentStatus.CAPTURED, PaymentRules.AuthorizationStatus.SUCCEEDED),
                new PaymentRules.State(PaymentStatus.VOIDED, PaymentRules.AuthorizationStatus.SUCCEEDED),
                new PaymentRules.State(PaymentStatus.EXPIRED, PaymentRules.AuthorizationStatus.SUCCEEDED));
    }

    // 필수 입력 누락은 평가 호출 전에 거절한다.
    @Test
    void evaluateAndDecide_whenStateOrInputNull_rejectsBeforeCallingEvaluator() {
        assertThatThrownBy(() -> service.evaluateAndDecide(null, input)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.evaluateAndDecide(PaymentRules.State.pending(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(evaluator);
    }

    // 비어 있는 응답으로는 결제 분기를 판단하지 않는다.
    @Test
    void evaluateAndDecide_whenResponseNull_throwsException() {
        when(evaluator.evaluate(any(FdsEvaluationRequest.class))).thenReturn(null);

        assertThatThrownBy(() -> service.evaluateAndDecide(PaymentRules.State.pending(), input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FDS 평가 응답이 없습니다");
        verify(evaluator).evaluate(any(FdsEvaluationRequest.class));
        verifyNoMoreInteractions(evaluator);
    }

    // 다른 요청의 정상 판정도 현재 결제의 승인 근거로 사용하지 않는다.
    @Test
    void evaluateAndDecide_whenRequestIdMismatch_throwsException() {
        when(evaluator.evaluate(any(FdsEvaluationRequest.class)))
                .thenReturn(response(FdsResult.ALLOW, UUID.fromString("00000000-0000-0000-0000-000000000999")));

        assertThatThrownBy(() -> service.evaluateAndDecide(PaymentRules.State.pending(), input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FDS 평가 응답의 요청 ID가 일치하지 않습니다");
        verify(evaluator).evaluate(any(FdsEvaluationRequest.class));
        verifyNoMoreInteractions(evaluator);
    }

    // 호출 실패는 판정으로 변환하거나 재시도하지 않는다.
    @Test
    void evaluateAndDecide_whenEvaluatorThrows_propagatesWithoutRetry() {
        var failure = new IllegalStateException("evaluation unavailable");
        when(evaluator.evaluate(any(FdsEvaluationRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluateAndDecide(PaymentRules.State.pending(), input))
                .isSameAs(failure);
        verify(evaluator).evaluate(any(FdsEvaluationRequest.class));
        verifyNoMoreInteractions(evaluator);
    }

    private FdsEvaluationResponse response(FdsResult result, UUID requestId) {
        return FdsEvaluationResponse.builder()
                .evaluationRequestId(requestId)
                .evaluationId(201L)
                .result(result)
                .riskScore(result == FdsResult.ERROR ? null : 0)
                .evaluatedAt(PaymentEvaluationFixtures.REQUESTED_AT.plusSeconds(1))
                .errorCode(result == FdsResult.ERROR ? "FDS_EVALUATION_FAILED" : null)
                .build();
    }
}
