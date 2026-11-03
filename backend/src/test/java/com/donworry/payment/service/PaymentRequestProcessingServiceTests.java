package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentProcessingStage;
import com.donworry.payment.entity.PaymentRequest;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.payment.repository.PaymentRequestRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentRequestProcessingServiceTests {
    // 접수된 요청을 재개할 때도 가맹점은 저장된 결제에서 복원한다.
    @Test
    void evaluationInput_whenReceived_preservesStoredMerchantOnRetry() {
        var payments = mock(PaymentRepository.class);
        var requests = mock(PaymentRequestRepository.class);
        var service = new PaymentRequestProcessingService(
                payments,
                requests,
                mock(PaymentAuthorizationService.class),
                mock(com.donworry.payment.repository.PaymentFdsRecoveryRepository.class));
        var request = mock(PaymentRequest.class);
        when(request.getProcessingStage()).thenReturn(PaymentProcessingStage.RECEIVED);
        when(request.getEvaluationRequestId()).thenReturn(PaymentEvaluationFixtures.REQUEST_ID);
        when(requests.findByPaymentId(101L)).thenReturn(Optional.of(request));
        var payment = Payment.pending(42L, "customer", "order", 100L, PaymentEvaluationFixtures.REQUESTED_AT);
        when(payments.findById(101L)).thenReturn(Optional.of(payment));

        var first = service.evaluationInput(101L).orElseThrow();
        var retry = service.evaluationInput(101L).orElseThrow();

        assertThat(first.merchantId()).isEqualTo(payment.getMerchantId()).isEqualTo(42L);
        assertThat(first.paymentId()).isEqualTo(101L);
        assertThat(first.evaluationRequestId()).isEqualTo(PaymentEvaluationFixtures.REQUEST_ID);
        assertThat(retry).isEqualTo(first);
    }
}
