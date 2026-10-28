package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.SnapshotIsolationException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaSystemException;

class PaymentRequestFlowServiceTests {
    private final PaymentRequestAdmissionService admission = mock(PaymentRequestAdmissionService.class);
    private final PaymentRequestProcessingService processing = mock(PaymentRequestProcessingService.class);
    private final PaymentEvaluationService evaluation = mock(PaymentEvaluationService.class);
    private final PaymentRequestFlowService flow =
            new PaymentRequestFlowService(admission, processing, evaluation, mock(FdsFailureReporter.class));
    private final PaymentRequestCommand command =
            new PaymentRequestCommand(1L, "order", "customer", 100L, Instant.now(), UUID.randomUUID());
    private final PaymentEvaluationInput input = PaymentEvaluationInput.builder()
            .evaluationRequestId(UUID.randomUUID())
            .paymentId(1L)
            .merchantId(1L)
            .customerRef("customer")
            .amount(100L)
            .paymentRequestedAt(Instant.now())
            .build();

    private void setup() {
        when(admission.admit(command))
                .thenReturn(new PaymentRequestAdmission(1L, 1L, input.evaluationRequestId(), false));
        when(processing.evaluationInput(1L)).thenReturn(Optional.of(input));
    }
    // 일시적 P2 스냅샷 경합은 같은 입력으로 다시 호출한다.
    @Test
    void submit_whenSnapshotRace_retriesSameInput() {
        setup();
        var error = new JpaSystemException(mock(SnapshotIsolationException.class));
        var response =
                new FdsEvaluationResponse(input.evaluationRequestId(), 1L, FdsResult.ALLOW, 0, Instant.now(), null);
        when(evaluation.evaluateObserved(input))
                .thenThrow(error)
                .thenReturn(new PaymentObservedEvaluation(response, Instant.now(), Instant.now()));
        flow.submit(command);
        verify(evaluation, times(2)).evaluateObserved(input);
        verify(processing)
                .saveEvaluation(eq(1L), argThat(value -> value.evaluation().equals(response)));
        verify(processing).authorize(1L);
    }
    // 같은 경합이 반복되면 최대 세 번 호출하고 접수는 남긴 채 오류를 전파한다.
    @Test
    void submit_whenRacePersists_boundsRetries() {
        setup();
        var error = new JpaSystemException(mock(SnapshotIsolationException.class));
        when(evaluation.evaluateObserved(input)).thenThrow(error);
        assertThatThrownBy(() -> flow.submit(command)).isSameAs(error);
        verify(evaluation, times(3)).evaluateObserved(input);
        verify(processing, never()).authorize(any());
    }
    // 일반 DB 오류를 일시적 경합으로 간주하거나 자동 승인하지 않는다.
    @Test
    void submit_whenOtherDatabaseError_propagatesWithoutRetry() {
        setup();
        var error = new org.springframework.dao.DataIntegrityViolationException("unexpected");
        when(evaluation.evaluateObserved(input)).thenThrow(error);
        assertThatThrownBy(() -> flow.submit(command)).isSameAs(error);
        verify(evaluation).evaluateObserved(input);
        verify(processing, never()).authorize(any());
    }
}
