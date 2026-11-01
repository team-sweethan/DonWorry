package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MerchantSecretServiceTests {
    private final MerchantIntegrationRepository integrations = mock(MerchantIntegrationRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final MerchantKeyCipher cipher = mock(MerchantKeyCipher.class);
    private final MerchantSecretService service = new MerchantSecretService(integrations, merchants, cipher);
    // 키 폐기 뒤 레거시 환경변수 참조가 남아도 되살리지 않는다
    @Test
    void resolve_whenRevoked_rejectsWithoutLegacyFallback() {
        var merchant = mock(Merchant.class);
        when(merchant.getMerchantId()).thenReturn(1L);
        when(merchant.getSecretReference()).thenReturn("DONWORRY_MERCHANT_HMAC_CAKESHOP");
        when(merchants.findByMerchantCode("CAKESHOP")).thenReturn(Optional.of(merchant));
        var integration = MerchantIntegration.empty(1L);
        integration.revoke(java.time.LocalDateTime.now());
        when(integrations.findByMerchantId(1L)).thenReturn(Optional.of(integration));
        assertThatThrownBy(() -> service.resolve("CAKESHOP"))
                .isInstanceOfSatisfying(
                        MerchantAuthenticationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED));
        verifyNoInteractions(cipher);
    }
    // 아직 이전하지 않은 키는 인증 실패로 숨기지 않고 설정 장애로 차단한다
    @Test
    void resolve_whenLegacyNotImported_returnsUnavailable() {
        var merchant = mock(Merchant.class);
        when(merchant.getMerchantId()).thenReturn(1L);
        when(merchant.getSecretReference()).thenReturn("DONWORRY_MERCHANT_HMAC_CAKESHOP");
        when(merchants.findByMerchantCode("CAKESHOP")).thenReturn(Optional.of(merchant));
        assertThatThrownBy(() -> service.resolve("CAKESHOP"))
                .isInstanceOfSatisfying(
                        MerchantAuthenticationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE));
    }
}
