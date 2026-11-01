package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.dto.PaymentCancellationResponse;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PaymentCancellationAdmissionServiceTests {
    private final PaymentCancellationTransactionService transaction = mock(PaymentCancellationTransactionService.class);
    private final PaymentCancellationAdmissionService service = new PaymentCancellationAdmissionService(transaction);

    private PaymentCancellationCommand command() {
        return new PaymentCancellationCommand(1L, 2L, UUID.randomUUID(), "취소");
    }

    private DataIntegrityViolationException duplicate(String name) {
        return new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException("duplicate", new SQLException("duplicate", "23000", 1062), name));
    }
    // 키 UNIQUE 경합만 새 트랜잭션 호출로 재확인한다
    @Test
    void admit_whenRequestKeyRaces_retriesAfterFailure() {
        var c = command();
        var result = PaymentCancellationResponse.builder().paymentId(2L).build();
        when(transaction.admit(c))
                .thenThrow(duplicate("uk_payment_cancellation_requests_merchant_key"))
                .thenReturn(result);
        assertThat(service.admit(c)).isSameAs(result);
        verify(transaction, times(2)).admit(c);
    }
    // 다른 무결성 오류는 키 충돌로 숨기거나 재시도하지 않는다
    @Test
    void admit_whenUnexpectedConstraintFails_propagates() {
        var c = command();
        var failure = duplicate("fk_payment_cancellations_source");
        when(transaction.admit(c)).thenThrow(failure);
        assertThatThrownBy(() -> service.admit(c)).isSameAs(failure);
        verify(transaction).admit(c);
    }
    // 반복 경합에도 무한 재시도하지 않는다
    @Test
    void admit_whenRacePersists_stopsAfterThreeAttempts() {
        var c = command();
        when(transaction.admit(c)).thenThrow(duplicate("uk_payment_cancellation_requests_merchant_key"));
        assertThatThrownBy(() -> service.admit(c)).isInstanceOf(DataIntegrityViolationException.class);
        verify(transaction, times(3)).admit(c);
    }
    // 형식 오류는 저장 트랜잭션 전에 거절한다
    @Test
    void command_whenInvalid_rejectsBeforeAdmission() {
        assertThatThrownBy(() -> new PaymentCancellationCommand(1L, -1L, UUID.randomUUID(), "취소"))
                .isInstanceOf(PaymentHttpException.class);
        assertThatThrownBy(() -> new PaymentCancellationCommand(1L, 2L, null, "취소"))
                .isInstanceOf(PaymentHttpException.class);
        assertThatThrownBy(() -> new PaymentCancellationCommand(1L, 2L, UUID.randomUUID(), "취소\u00a0"))
                .isInstanceOf(PaymentHttpException.class);
        verifyNoInteractions(transaction);
    }
}
