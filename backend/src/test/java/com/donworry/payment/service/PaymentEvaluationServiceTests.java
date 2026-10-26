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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class PaymentEvaluationServiceTests {

    private final FdsEvaluator evaluator = mock(FdsEvaluator.class);
    private final PaymentEvaluationService service = new PaymentEvaluationService(evaluator);

    // 모든 판정에서 원래 요청값을 한 번 전달하고 응답을 그대로 반환한다.
    @ParameterizedTest
    @EnumSource(FdsResult.class)
    void evaluate_whenEvaluatorReturnsResult_mapsInputAndReturnsResponse(FdsResult result) {
        var input = PaymentEvaluationFixtures.validInput().build();
        var response = FdsEvaluationResponse.builder()
                .evaluationRequestId(input.evaluationRequestId())
                .evaluationId(201L)
                .result(result)
                .riskScore(result == FdsResult.ERROR ? null : 60)
                .evaluatedAt(PaymentEvaluationFixtures.REQUESTED_AT.plusSeconds(1))
                .errorCode(result == FdsResult.ERROR ? "FDS_EVALUATION_FAILED" : null)
                .build();
        when(evaluator.evaluate(any(FdsEvaluationRequest.class))).thenReturn(response);

        var actual = service.evaluate(input);

        assertThat(actual).isSameAs(response);
        var captor = ArgumentCaptor.forClass(FdsEvaluationRequest.class);
        verify(evaluator).evaluate(captor.capture());
        var request = captor.getValue();
        assertThat(request.evaluationRequestId()).isEqualTo(input.evaluationRequestId());
        assertThat(request.paymentId()).isEqualTo(input.paymentId());
        assertThat(request.merchantId()).isEqualTo(input.merchantId());
        assertThat(request.customerRef()).isEqualTo(input.customerRef());
        assertThat(request.amount()).isEqualTo(input.amount());
        assertThat(request.paymentRequestedAt()).isEqualTo(input.paymentRequestedAt());
        verifyNoMoreInteractions(evaluator);
    }

    // 입력이 없으면 P2에 요청하지 않고 계약 위반을 알린다.
    @Test
    void evaluate_whenInputNull_throwsWithoutCallingEvaluator() {
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(evaluator);
    }

    // 유효하지 않은 입력은 생성 단계에서 거절되어 평가 호출까지 도달하지 않는다.
    @Test
    void evaluate_whenAmountInvalid_throwsWithoutCallingEvaluator() {
        assertThatThrownBy(() -> service.evaluate(
                        PaymentEvaluationFixtures.validInput().amount(0).build()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(evaluator);
    }

    // P2 실패를 성공으로 바꾸거나 재시도하지 않고 원래 예외를 전달한다.
    @Test
    void evaluate_whenEvaluatorThrows_propagatesWithoutRetry() {
        var input = PaymentEvaluationFixtures.validInput().build();
        var failure = new IllegalStateException("evaluation unavailable");
        when(evaluator.evaluate(any(FdsEvaluationRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.evaluate(input)).isSameAs(failure);
        verify(evaluator).evaluate(any(FdsEvaluationRequest.class));
        verifyNoMoreInteractions(evaluator);
    }
}
