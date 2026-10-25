package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureCompleted;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentCaptureServiceTests {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentCaptureService service = new PaymentCaptureService(
            payments,
            mock(com.donworry.payment.repository.PaymentActionRepository.class),
            mock(org.springframework.context.ApplicationEventPublisher.class));
    private final CaptureCompleted event = new CaptureCompleted(10L, 20L, Instant.now());
    private final Payment payment = Payment.pending(1L, "customer", "order", 1000L, Instant.now());

    private void stored() {
        when(payments.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
    }

    // 승인된 결제의 중복 완료는 상태와 원래 승인 참조·금액을 보존한다.
    @Test
    void complete_whenRepeated_preservesCapturedPayment() {
        stored();
        payment.recordAuthorizationSuccess("auth-10", false);
        service.complete(event);
        service.complete(event);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getAuthorizationRef()).isEqualTo("auth-10");
        assertThat(payment.getAmount()).isEqualTo(1000L);
        verify(payments, times(2)).findByIdForUpdate(10L);
        verifyNoMoreInteractions(payments);
    }

    // 허용되지 않은 상태는 승인 참조가 있어도 매입 완료로 바꾸지 않는다.
    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"HELD", "DENIED", "DECLINED", "VOIDED", "EXPIRED"})
    void complete_whenStateNotEligible_rejectsWithoutMutation(PaymentStatus status) {
        stored();
        payment.recordAuthorizationSuccess("auth", false);
        ReflectionTestUtils.setField(payment, "status", status);
        assertThatThrownBy(() -> service.complete(event)).isInstanceOf(IllegalStateException.class);
        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getAuthorizationRef()).isEqualTo("auth");
    }

    // 미승인 결제와 손상된 CAPTURED 모두 정상 완료로 취급하지 않는다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "oversized"})
    void complete_whenReferenceInvalid_rejectsPendingAndCaptured(String reference) {
        stored();
        String value = "oversized".equals(reference) ? "a".repeat(101) : reference;
        ReflectionTestUtils.setField(payment, "authorizationRef", value);
        for (var status : new PaymentStatus[] {PaymentStatus.PENDING, PaymentStatus.CAPTURED}) {
            ReflectionTestUtils.setField(payment, "status", status);
            assertThatThrownBy(() -> service.complete(event)).isInstanceOf(IllegalStateException.class);
            assertThat(payment.getStatus()).isEqualTo(status);
        }
    }

    // 이벤트 필수값 오류는 DB를 조회하기 전에 거절한다.
    @ParameterizedTest
    @MethodSource("invalidEvents")
    void complete_whenEventInvalid_rejectsBeforeLookup(CaptureCompleted invalid) {
        assertThatThrownBy(() -> service.complete(invalid)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(payments);
    }

    static Stream<CaptureCompleted> invalidEvents() {
        return Stream.of(
                null,
                new CaptureCompleted(null, 1L, Instant.now()),
                new CaptureCompleted(0L, 1L, Instant.now()),
                new CaptureCompleted(-1L, 1L, Instant.now()),
                new CaptureCompleted(1L, null, Instant.now()),
                new CaptureCompleted(1L, 0L, Instant.now()),
                new CaptureCompleted(1L, -1L, Instant.now()),
                new CaptureCompleted(1L, 1L, null));
    }

    // 없는 결제는 성공으로 무시하지 않는다.
    @Test
    void complete_whenPaymentMissing_throwsException() {
        assertThatThrownBy(() -> service.complete(event)).isInstanceOf(IllegalStateException.class);
        verify(payments).findByIdForUpdate(10L);
    }
}
