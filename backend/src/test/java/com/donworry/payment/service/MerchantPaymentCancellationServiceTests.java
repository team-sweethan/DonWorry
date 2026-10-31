package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MerchantPaymentCancellationServiceTests {
    // 정지 가맹점도 인증된 기존 결제 취소·조회는 허용하지만 신규 결제는 계속 차단한다
    @Test
    void request_whenInactiveMerchant_authenticatesAndKeepsNewPaymentBlocked() {
        ObjectProvider<PaymentMerchantIdentity> provider = mock(ObjectProvider.class);
        var identity = mock(PaymentMerchantIdentity.class);
        var tenants = mock(TenantReader.class);
        when(provider.getIfAvailable()).thenReturn(identity);
        when(identity.currentMerchantId()).thenReturn(Optional.of(7L));
        when(tenants.findById(7L))
                .thenReturn(Optional.of(new TenantSummary(7L, "merchant", "INACTIVE", BigDecimal.ZERO)));
        var access = new PaymentMerchantAccess(provider, tenants);
        var admission = mock(PaymentCancellationAdmissionService.class);
        var query = mock(PaymentCancellationQueryService.class);
        var service = new MerchantPaymentCancellationService(access, admission, query);
        var key = UUID.randomUUID();
        service.request(9L, key, "취소");
        service.find(key);
        verify(admission).admit(new PaymentCancellationCommand(7L, 9L, key, "취소"));
        verify(query).find(7L, key);
        assertThatThrownBy(() -> access.requireMerchant(true))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo("MERCHANT_INACTIVE"));
        when(identity.currentMerchantId()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.request(9L, UUID.randomUUID(), "취소"))
                .isInstanceOfSatisfying(
                        PaymentHttpException.class, e -> assertThat(e.getCode()).isEqualTo("PAYMENT_AUTH_REQUIRED"));
        verifyNoMoreInteractions(admission, query);
    }
}
