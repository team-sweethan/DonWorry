package com.donworry.identity.service;

import com.donworry.identity.dto.MerchantKeyMaterial;
import com.donworry.identity.entity.IntegrationStatus;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MerchantSecretService {
    private final MerchantIntegrationRepository integrations;
    private final MerchantRepository merchants;
    private final MerchantKeyCipher cipher;

    @Transactional(readOnly = true)
    public MerchantKeyMaterial resolve(String merchantCode) {
        var merchant = merchants
                .findByMerchantCode(merchantCode)
                .orElseThrow(() -> new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED));
        var integration = integrations
                .findByMerchantId(merchant.getMerchantId())
                .orElseThrow(() -> new MerchantAuthenticationException(
                        merchant.getSecretReference() == null
                                ? AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED
                                : AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE));
        if (integration.getStatus() == IntegrationStatus.NO_KEY)
            throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED);
        return new MerchantKeyMaterial(
                cipher.decrypt(
                        merchant.getMerchantId(),
                        integration.getKeyVersion(),
                        integration.getMasterKeyId(),
                        integration.getEncryptedSecret(),
                        integration.getEncryptionNonce()),
                integration.getKeyVersion());
    }
}
