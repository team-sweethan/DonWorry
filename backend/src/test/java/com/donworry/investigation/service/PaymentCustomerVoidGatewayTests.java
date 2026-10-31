package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.investigation.entity.ActionType;
import com.donworry.investigation.service.mock.*;
import com.donworry.payment.api.*;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCustomerVoidGatewayTests {
    // 고객 취소로 접수되지 않은 직원 요청은 오지 않을 직원 결과 이벤트를 기다리지 않는다.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "PAYMENT_CANCELED_BY_CUSTOMER",
                "PAYMENT_CANCELLATION_IN_PROGRESS",
                "PAYMENT_CANCELLATION_REVIEW_REQUIRED"
            })
    void request_whenCustomerCancellationBlocks_returnsResponseOnly(String code) {
        var requester = mock(PaymentActionRequester.class);
        var gateway = new PaymentFinancialActionGateway(
                requester, mock(MockPaymentInfoStore.class), mock(MockFinancialActionGateway.class));
        when(requester.request(any())).thenReturn(PaymentActionReceipt.rejected(code));
        var receipt =
                gateway.request(new FinancialActionGateway.Command(1L, 2L, ActionType.CAPTURE, UUID.randomUUID()));
        assertThat(receipt.status()).isEqualTo(FinancialActionGateway.Receipt.Status.REJECTED);
        assertThat(receipt.errorCode()).isEqualTo(code);
        assertThat(receipt.resultEventFollows()).isFalse();
    }
}
