package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PaymentRequestAdmissionServiceTests {
    private final PaymentRequestTransactionService transaction = mock(PaymentRequestTransactionService.class);
    private final PaymentRequestAdmissionService service = new PaymentRequestAdmissionService(transaction);

    private PaymentRequestCommand command() {
        return new PaymentRequestCommand(1L, "order", "customer", 100L, Instant.now(), UUID.randomUUID());
    }

    private DataIntegrityViolationException duplicate(String name) {
        return new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException("duplicate", new SQLException("duplicate", "23000", 1062), name));
    }

    // 주문과 요청 키 경합은 실패한 트랜잭션이 끝난 후 제한된 횟수로 재조회한다.
    @Test
    void admit_whenKnownUniquenessRace_retriesTransaction() {
        var c = command();
        var receipt = new PaymentRequestAdmission(1L, 1L, UUID.randomUUID(), false);
        when(transaction.admit(c))
                .thenThrow(duplicate("uk_payment_orders_merchant_order"))
                .thenThrow(duplicate("uk_payment_requests_merchant_key"))
                .thenReturn(receipt);
        assertThat(service.admit(c)).isEqualTo(receipt);
        verify(transaction, times(3)).admit(c);
    }

    // 시도 순번 등 예상 밖 무결성 오류를 요청 중복으로 숨기지 않는다.
    @Test
    void admit_whenUnexpectedIntegrityError_propagatesWithoutRetry() {
        var c = command();
        var error = duplicate("uk_payment_requests_order_attempt");
        when(transaction.admit(c)).thenThrow(error);
        assertThatThrownBy(() -> service.admit(c)).isSameAs(error);
        verify(transaction).admit(c);
    }

    // 계속되는 오류로 무한 재시도하지 않는다.
    @Test
    void admit_whenRacePersists_stopsAfterThreeTransactions() {
        var c = command();
        var error = duplicate("uk_payment_requests_merchant_key");
        when(transaction.admit(c)).thenThrow(error);
        assertThatThrownBy(() -> service.admit(c)).isSameAs(error);
        verify(transaction, times(3)).admit(c);
    }

    // 필수 입력과 양끝 공백은 DB 트랜잭션 전에 거절한다.
    @Test
    void command_whenInvalid_rejectsInput() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new PaymentRequestCommand(0L, "order", "customer", 100L, Instant.now(), UUID.randomUUID()));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new PaymentRequestCommand(1L, " order", "customer", 100L, Instant.now(), UUID.randomUUID()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new PaymentRequestCommand(1L, "order", "customer", 0L, Instant.now(), UUID.randomUUID()));
        assertThatNullPointerException().isThrownBy(() -> service.admit(null));
        verifyNoInteractions(transaction);
    }
}
