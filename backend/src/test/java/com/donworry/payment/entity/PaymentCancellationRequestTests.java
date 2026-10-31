package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCancellationRequestTests {
    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    private Payment payment() {
        var payment = mock(Payment.class);
        when(payment.getPaymentId()).thenReturn(1L);
        when(payment.getMerchantId()).thenReturn(2L);
        return payment;
    }

    // 같은 판단 재전송은 시각을 유지하고 명시적 재판단만 상태를 바꾼다
    @Test
    void reconsider_whenDeferred_preservesOriginalContentAndUpdatesOnlyChangedDecision() {
        var payment = payment();
        var key = UUID.randomUUID();
        var request = PaymentCancellationRequest.recordDecision(
                payment, key, "취소 A", PaymentCancellationResolution.DEFERRED, null, NOW);
        request.reconsider(payment, PaymentCancellationResolution.DEFERRED, null, NOW.plusSeconds(1));
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
        request.reconsider(payment, PaymentCancellationResolution.REVIEW_REQUIRED, null, NOW.plusSeconds(2));
        assertThat(request.getResponseCode()).isEqualTo("PAYMENT_REVIEW_REQUIRED");
        request.reconsider(payment, PaymentCancellationResolution.ACCEPTED, null, NOW.plusSeconds(3));
        assertThat(request.getResponseCode()).isNull();
        assertThat(request.getRequestKey()).isEqualTo(key);
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.matches(1L, "취소 A")).isTrue();
        assertThat(request.matches(1L, "취소 a")).isFalse();
        assertThat(request.matches(2L, "취소 A")).isFalse();
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        request.reconsider(payment, PaymentCancellationResolution.DEFERRED, null, NOW.plusSeconds(4)));
    }

    // 확정 거절은 재판단하지 않는다
    @Test
    void reconsider_whenRejected_rejectsTransition() {
        var payment = payment();
        var request = PaymentCancellationRequest.recordDecision(
                payment, UUID.randomUUID(), "취소", PaymentCancellationResolution.REJECTED, null, NOW);
        assertThatIllegalStateException()
                .isThrownBy(() -> request.reconsider(payment, PaymentCancellationResolution.ACCEPTED, null, NOW));
    }

    // Unicode 공백과 빈 사유는 자르거나 정상화하지 않고 거절한다
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " 취소", "취소\n", "\u00a0취소", "취소\u3000"})
    void recordDecision_whenReasonInvalid_rejects(String reason) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentCancellationRequest.recordDecision(
                        payment(), UUID.randomUUID(), reason, PaymentCancellationResolution.DEFERRED, null, NOW));
    }

    // 사유 길이는 UTF-16 길이 대신 유니코드 코드포인트로 센다
    @Test
    void recordDecision_whenSupplementaryCharacters_countsCodePoints() {
        var reason = "😀".repeat(200);
        assertThat(PaymentCancellationRequest.recordDecision(
                                payment(), UUID.randomUUID(), reason, PaymentCancellationResolution.DEFERRED, null, NOW)
                        .getReason())
                .isEqualTo(reason);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentCancellationRequest.recordDecision(
                        payment(), UUID.randomUUID(), reason + "가", PaymentCancellationResolution.DEFERRED, null, NOW));
    }

    // 기존 취소의 결제 상태와 직원 성공 근거가 맞아야 연결한다
    @Test
    void recordDecision_whenLegacyVoid_checksPaymentAndActionEvidence() {
        var payment = payment();
        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentCancellationRequest.recordDecision(
                        payment, UUID.randomUUID(), "취소", PaymentCancellationResolution.LEGACY_VOID, null, NOW));
        when(payment.getStatus()).thenReturn(PaymentStatus.VOIDED);
        var request = PaymentCancellationRequest.recordDecision(
                payment, UUID.randomUUID(), "취소", PaymentCancellationResolution.LEGACY_VOID, null, NOW);
        assertThat(request.getExistingVoidActionId()).isNull();
        var action = mock(PaymentAction.class);
        when(action.getActionId()).thenReturn(8L);
        when(action.getPaymentId()).thenReturn(1L);
        when(action.getActionType()).thenReturn(PaymentActionType.VOID);
        when(action.getStatus()).thenReturn(PaymentActionStatus.SUCCEEDED);
        assertThat(PaymentCancellationRequest.recordDecision(
                                payment,
                                UUID.randomUUID(),
                                "취소",
                                PaymentCancellationResolution.LEGACY_VOID,
                                action,
                                NOW)
                        .getExistingVoidActionId())
                .isEqualTo(8L);
        when(action.getPaymentId()).thenReturn(9L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentCancellationRequest.recordDecision(
                        payment, UUID.randomUUID(), "취소", PaymentCancellationResolution.LEGACY_VOID, action, NOW));
    }
}
