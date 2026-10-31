package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.api.PaymentCustomerVoidSummary;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentCustomerVoidReaderServiceTests {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentCancellationRepository works = mock(PaymentCancellationRepository.class);
    private final PaymentCancellationRequestRepository requests = mock(PaymentCancellationRequestRepository.class);
    private final PaymentCustomerVoidReaderService reader =
            new PaymentCustomerVoidReaderService(payments, works, requests);
    private final Instant now = Instant.parse("2026-10-31T00:00:00.123456Z");

    private Payment payment() {
        var p = Payment.pending(2L, "customer", "order", 2500L, now);
        ReflectionTestUtils.setField(p, "paymentId", 10L);
        ReflectionTestUtils.setField(p, "status", PaymentStatus.VOIDED);
        ReflectionTestUtils.setField(p, "authorizationRef", "auth");
        ReflectionTestUtils.setField(p, "statusChangedAt", now);
        when(payments.findByPaymentIdAndMerchantId(10L, 2L)).thenReturn(Optional.of(p));
        return p;
    }

    private PaymentCancellation work(Payment p) {
        var request = PaymentCancellationRequest.recordDecision(
                p, UUID.randomUUID(), "reason", PaymentCancellationResolution.ACCEPTED, null, now);
        ReflectionTestUtils.setField(request, "requestId", 20L);
        var work = PaymentCancellation.start(request, p, PaymentCancellationOperation.VOID, null, now);
        ReflectionTestUtils.setField(work, "cancellationId", 30L);
        work.succeed(now);
        when(works.findByPaymentId(10L)).thenReturn(Optional.of(work));
        when(requests.findById(20L)).thenReturn(Optional.of(request));
        return work;
    }

    // 고객 성공만 저장된 식별자·금액·시각으로 반환하며 반복 조회는 쓰기를 하지 않는다.
    @Test
    void findSucceeded_whenCustomerVoidSucceeded_returnsStoredResult() {
        var p = payment();
        work(p);
        var expected = new PaymentCustomerVoidSummary(30L, 10L, 2L, 2500L, now);
        assertThat(reader.findSucceeded(2L, 10L)).contains(expected);
        assertThat(reader.findSucceeded(2L, 10L)).contains(expected);
        verify(payments, times(2)).findByPaymentIdAndMerchantId(10L, 2L);
        verify(works, times(2)).findByPaymentId(10L);
        verify(requests, times(2)).findById(20L);
        verifyNoMoreInteractions(payments, works, requests);
    }

    // 직원 취소를 포함하여 고객 작업이 없는 결제는 고객 취소 성공으로 보지 않는다.
    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void findSucceeded_whenNoCustomerWork_returnsEmpty(PaymentStatus status) {
        var p = payment();
        ReflectionTestUtils.setField(p, "status", status);
        assertThat(reader.findSucceeded(2L, 10L)).isEmpty();
        verifyNoInteractions(requests);
    }

    // 실패·진행·결과 불명은 성공으로 반환하지 않는다.
    @ParameterizedTest
    @EnumSource(
            value = PaymentCancellationStatus.class,
            names = {"PROCESSING", "UNKNOWN", "FAILED"})
    void findSucceeded_whenNotSucceeded_returnsEmpty(PaymentCancellationStatus status) {
        var w = work(payment());
        ReflectionTestUtils.setField(w, "status", status);
        assertThat(reader.findSucceeded(2L, 10L)).isEmpty();
        verify(requests, never()).findById(any());
    }

    // 매입 후 환불은 승인 취소 성공 조회에서 제외한다.
    @Test
    void findSucceeded_whenRefund_returnsEmpty() {
        var w = work(payment());
        ReflectionTestUtils.setField(w, "operation", PaymentCancellationOperation.REFUND);
        assertThat(reader.findSucceeded(2L, 10L)).isEmpty();
    }

    // 잘못된 식별자는 저장소 접근 전에 차단한다.
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void findSucceeded_whenInvalidId_rejectsBeforeQuery(Long id) {
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findSucceeded(id, 10L));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findSucceeded(2L, id));
        verifyNoInteractions(payments, works, requests);
    }

    // 없는 결제와 다른 가맹점 결제는 정상 미취소 결과로 숨기지 않는다.
    @Test
    void findSucceeded_whenNotOwned_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findSucceeded(2L, 10L));
        verifyNoInteractions(works, requests);
    }

    // 결제 상태·금액·완료 시각이 성공 원본과 다르면 데이터 오류로 남긴다.
    @ParameterizedTest
    @ValueSource(strings = {"status", "amount", "statusChangedAt", "authorizationRef"})
    void findSucceeded_whenPaymentInconsistent_throws(String field) {
        var p = payment();
        work(p);
        Object value = switch (field) {
            case "status" -> PaymentStatus.HELD;
            case "amount" -> 999L;
            case "statusChangedAt" -> now.plusSeconds(1);
            default -> "";
        };
        ReflectionTestUtils.setField(p, field, value);
        assertThatIllegalStateException().isThrownBy(() -> reader.findSucceeded(2L, 10L));
    }

    // 성공 작업의 원 요청이 없거나 다른 결제·가맹점·접수 상태이면 거절한다.
    @ParameterizedTest
    @ValueSource(strings = {"missing", "paymentId", "merchantId", "resolution"})
    void findSucceeded_whenSourceInconsistent_throws(String field) {
        work(payment());
        var source = requests.findById(20L).orElseThrow();
        if (field.equals("missing")) when(requests.findById(20L)).thenReturn(Optional.empty());
        else
            ReflectionTestUtils.setField(
                    source, field, field.equals("resolution") ? PaymentCancellationResolution.LINKED : 999L);
        assertThatIllegalStateException().isThrownBy(() -> reader.findSucceeded(2L, 10L));
    }

    // 저장소 장애는 성공 없음으로 바꾸지 않고 전파한다.
    @Test
    void findSucceeded_whenDatabaseFails_propagates() {
        payment();
        var failure = new DataAccessResourceFailureException("test failure");
        when(works.findByPaymentId(10L)).thenThrow(failure);
        assertThatThrownBy(() -> reader.findSucceeded(2L, 10L)).isSameAs(failure);
    }
}
