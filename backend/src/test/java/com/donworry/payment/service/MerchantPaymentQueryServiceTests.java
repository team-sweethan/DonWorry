package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.repository.*;
import org.junit.jupiter.api.Test;

class MerchantPaymentQueryServiceTests {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentOrderRepository orders = mock(PaymentOrderRepository.class);
    private final PaymentRequestRepository requests = mock(PaymentRequestRepository.class);
    private final MerchantPaymentQueryService query = new MerchantPaymentQueryService(payments, orders, requests);

    // 유효하지 않은 조회 식별자와 페이지 조건은 저장소 호출 전에 거절한다.
    @Test
    void query_whenInvalid_rejectsBeforeReading() {
        assertThatIllegalArgumentException().isThrownBy(() -> query.findPayment(null, 1L));
        assertThatIllegalArgumentException().isThrownBy(() -> query.findPayment(1L, 0L));
        assertThatIllegalArgumentException().isThrownBy(() -> query.findOrder(0L, "order", 20, null));
        assertThatIllegalArgumentException().isThrownBy(() -> query.findOrder(1L, " order", 20, null));
        assertThatIllegalArgumentException().isThrownBy(() -> query.findOrder(1L, "a".repeat(101), 20, null));
        assertThatIllegalArgumentException().isThrownBy(() -> query.findOrder(1L, "order", 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> query.findOrder(1L, "order", 101, null));
        assertThatIllegalArgumentException().isThrownBy(() -> query.findOrder(1L, "order", 20, 0L));
        verifyNoInteractions(payments, orders, requests);
    }
}
