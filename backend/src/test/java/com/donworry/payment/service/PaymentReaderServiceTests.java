package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.api.PaymentSummary;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentReaderServiceTests {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentReaderService reader = new PaymentReaderService(payments);

    // 모든 결제 상태를 필터링 없이 반환하며 최초 저장 시각과 승인 참조를 바꾸지 않는다.
    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void findById_whenPaymentExists_returnsStoredValuesWithoutMutation(PaymentStatus status) {
        var createdAt = Instant.parse("2026-10-25T01:02:03.123456Z");
        var payment = Payment.pending(2L, "customer", "order", 123_456L, createdAt);
        ReflectionTestUtils.setField(payment, "paymentId", 10L);
        ReflectionTestUtils.setField(payment, "status", status);
        ReflectionTestUtils.setField(payment, "authorizationRef", "original-auth");
        when(payments.findById(10L)).thenReturn(Optional.of(payment));

        assertThat(reader.findById(10L)).contains(new PaymentSummary(10L, 123_456L, status.name(), createdAt));
        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getAuthorizationRef()).isEqualTo("original-auth");
        assertThat(payment.getAmount()).isEqualTo(123_456L);
        assertThat(payment.getCreatedAt()).isEqualTo(createdAt);
        verify(payments).findById(10L);
        verifyNoMoreInteractions(payments);
    }

    // 잘못된 ID는 DB 조회 전에 거절한다.
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void findById_whenIdInvalid_rejectsBeforeQuery(Long id) {
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findById(id));
        verifyNoInteractions(payments);
    }

    // 없는 결제는 빈 결과로 반환한다.
    @Test
    void findById_whenPaymentMissing_returnsEmpty() {
        when(payments.findById(10L)).thenReturn(Optional.empty());
        assertThat(reader.findById(10L)).isEmpty();
        verify(payments).findById(10L);
        verifyNoMoreInteractions(payments);
    }

    // DB 장애는 없는 결제로 바꾸지 않고 원래 예외를 전달한다.
    @Test
    void findById_whenDatabaseFails_propagatesOriginalFailure() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(payments.findById(10L)).thenThrow(failure);
        assertThatThrownBy(() -> reader.findById(10L)).isSameAs(failure);
        verify(payments).findById(10L);
        verifyNoMoreInteractions(payments);
    }
}
