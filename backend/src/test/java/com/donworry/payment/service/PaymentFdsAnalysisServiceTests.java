package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.*;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentFdsAnalysisServiceTests {
    // 모든 쓰기 경로와 같은 순서로 잠그며 재평가나 승인 대신 복구 행만 갱신한다
    @Test
    void receive_locksInOrderAndRecordsAnalysis() {
        var payments = mock(PaymentRepository.class);
        var requests = mock(PaymentRequestRepository.class);
        var recoveries = mock(PaymentFdsRecoveryRepository.class);
        var row = mock(PaymentFdsRecovery.class);
        when(payments.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(Payment.class)));
        when(requests.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(mock(PaymentRequest.class)));
        when(recoveries.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(row));
        var event = new FdsEvaluationFailureAnalyzed(
                2L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                FdsFailureDisposition.REEVALUATION_AVAILABLE,
                "TEST",
                Instant.now());
        new PaymentFdsAnalysisService(payments, requests, recoveries).receive(event);
        var order = inOrder(payments, requests, recoveries, row);
        order.verify(payments).findByIdForUpdate(1L);
        order.verify(requests).findByPaymentIdForUpdate(1L);
        order.verify(recoveries).findByPaymentIdForUpdate(1L);
        order.verify(row).recordAnalysis(eq(event), any(Instant.class));
        verifyNoMoreInteractions(payments, requests, recoveries, row);
    }
    // 없는 결제는 성공 처리하거나 복구 행을 새로 만들지 않는다
    @Test
    void receive_whenPaymentMissing_throws() {
        var payments = mock(PaymentRepository.class);
        var requests = mock(PaymentRequestRepository.class);
        var recoveries = mock(PaymentFdsRecoveryRepository.class);
        var event = new FdsEvaluationFailureAnalyzed(
                2L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                FdsFailureDisposition.REEVALUATION_AVAILABLE,
                "TEST",
                Instant.now());
        assertThatIllegalStateException()
                .isThrownBy(() -> new PaymentFdsAnalysisService(payments, requests, recoveries).receive(event));
        verifyNoInteractions(requests, recoveries);
    }
}
