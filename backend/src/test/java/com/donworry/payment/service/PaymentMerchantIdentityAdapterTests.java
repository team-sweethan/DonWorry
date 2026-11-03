package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentMerchantIdentityAdapterTests {
    // P5는 요청의 merchantId나 직원 세션 대신 P1의 인증 결과만 읽는다
    @Test
    void currentMerchantId_whenIdentityChanges_usesOnlyPublicContract() {
        var current = mock(CurrentMerchant.class);
        var adapter = new PaymentMerchantIdentityAdapter(current);
        when(current.findCurrent()).thenReturn(Optional.empty(), Optional.of(new MerchantIdentity(7L)));
        assertThat(adapter.currentMerchantId()).isEmpty();
        assertThat(adapter.currentMerchantId()).contains(7L);
    }
}
