package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.entity.*;
import com.donworry.settlement.api.CaptureSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCancellationDecisionServiceTests {
    private final PaymentCancellationDecisionService service = new PaymentCancellationDecisionService();

    private Payment payment(PaymentStatus status, String authorization) {
        var payment = mock(Payment.class);
        when(payment.getPaymentId()).thenReturn(1L);
        when(payment.getMerchantId()).thenReturn(2L);
        when(payment.getAmount()).thenReturn(100L);
        when(payment.getStatus()).thenReturn(status);
        when(payment.getAuthorizationRef()).thenReturn(authorization);
        return payment;
    }

    private PaymentRequest original(PaymentProcessingStage stage, String result) {
        var request = mock(PaymentRequest.class);
        when(request.getPaymentId()).thenReturn(1L);
        when(request.getMerchantId()).thenReturn(2L);
        when(request.getProcessingStage()).thenReturn(stage);
        when(request.getEvaluationResult()).thenReturn(result);
        return request;
    }

    // 자동 매입 대기는 일시 거절하고 누락·모순된 기록은 확인 필요로 구분한다
    @Test
    void decide_whenAutoCapturePending_requiresConsistentOriginal() {
        var payment = payment(PaymentStatus.PENDING, "AUTH");
        assertThat(service.decide(payment, original(PaymentProcessingStage.AUTHORIZED, "ALLOW"), null, null))
                .isEqualTo(PaymentCancellationResolution.DEFERRED);
        assertThat(service.decide(payment, original(PaymentProcessingStage.AUTHORIZED, "DENY"), null, null))
                .isEqualTo(PaymentCancellationResolution.REVIEW_REQUIRED);
        assertThat(service.decide(payment, null, null, null)).isEqualTo(PaymentCancellationResolution.REVIEW_REQUIRED);
        assertThat(service.decide(payment(PaymentStatus.EXPIRED, "AUTH"), null, null, null))
                .isEqualTo(PaymentCancellationResolution.REVIEW_REQUIRED);
    }

    // 실패한 직원 조치나 승인 근거 없는 HELD는 취소 후보로 실행하지 않는다
    @Test
    void decide_whenFailedStaffActionOrMissingAuthorization_requiresReview() {
        var payment = payment(PaymentStatus.HELD, "AUTH");
        var action = mock(PaymentAction.class);
        when(action.getPaymentId()).thenReturn(1L);
        when(action.getActionType()).thenReturn(PaymentActionType.VOID);
        when(action.getStatus()).thenReturn(PaymentActionStatus.FAILED);
        assertThat(service.decide(payment, null, action, null))
                .isEqualTo(PaymentCancellationResolution.REVIEW_REQUIRED);
        assertThat(service.decide(payment(PaymentStatus.HELD, null), null, null, null))
                .isEqualTo(PaymentCancellationResolution.REVIEW_REQUIRED);
    }

    // 정산 상태는 환불 후보 판단에 영향을 주지 않으며 실행 미준비 응답을 유지한다
    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULED", "COMPLETED", "CANCELED"})
    void decide_whenCaptured_ignoresSettlementStatus(String settlementStatus) {
        var payment = payment(PaymentStatus.CAPTURED, "AUTH");
        var capture = new CaptureSnapshot(1L, 3L, "COMPLETED", 100L, Instant.now(), true, settlementStatus);
        assertThatThrownBy(() -> service.decide(payment, null, null, capture))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo("CANCELLATION_NOT_READY"));
    }

    // 매입 금액·완료 시각이 맞지 않는 CAPTURED는 복구 확인이 필요하다
    @Test
    void decide_whenCaptureEvidenceIncomplete_requiresReview() {
        var payment = payment(PaymentStatus.CAPTURED, "AUTH");
        assertThat(service.decide(
                        payment,
                        null,
                        null,
                        new CaptureSnapshot(1L, 3L, "COMPLETED", 200L, Instant.now(), true, "SCHEDULED")))
                .isEqualTo(PaymentCancellationResolution.REVIEW_REQUIRED);
        assertThat(service.decide(
                        payment, null, null, new CaptureSnapshot(1L, 3L, "COMPLETED", 100L, null, true, "SCHEDULED")))
                .isEqualTo(PaymentCancellationResolution.REVIEW_REQUIRED);
    }
}
