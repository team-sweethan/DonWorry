package com.donworry.identity.service;

import com.donworry.identity.dto.MerchantSignedRequest;
import com.donworry.identity.entity.IntegrationStatus;
import com.donworry.identity.entity.MerchantStatus;
import com.donworry.identity.repository.MerchantIntegrationRepository;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.MerchantAuthenticationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MerchantConnectionService {
    private final MerchantRepository merchants;
    private final MerchantIntegrationRepository integrations;
    private final Clock identityClock;

    // nonce 소비 후 별도 트랜잭션에서 검사하므로 거절한 요청도 재사용할 수 없다.
    @Transactional
    public void authorize(Long merchantId, long keyVersion, MerchantSignedRequest input) {
        // 조회·픽업은 nonce 소비 때 키 버전을 확인했으므로 가맹점 잠금으로 서로 기다리지 않는다.
        if (!"POST".equals(input.method())) return;
        var merchant = merchants
                .lockById(merchantId)
                .orElseThrow(() -> failure(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED));
        var integration = integrations
                .findByMerchantId(merchantId)
                .filter(i -> i.getStatus() != IntegrationStatus.NO_KEY && i.getKeyVersion() == keyVersion)
                .orElseThrow(() -> failure(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED));
        if (merchant.getStatus() != MerchantStatus.ACTIVE) throw failure(AuthErrorCode.MERCHANT_INACTIVE);
        String path = input.target().split("\\?", 2)[0];
        if (!path.equals("/api/merchant-connections/check") && !path.equals("/api/payments"))
            throw failure(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED);
        if (path.equals("/api/merchant-connections/check")) {
            if (input.body().length != 0) throw failure(AuthErrorCode.MERCHANT_CONNECTION_INVALID);
            integration.connect(LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC));
        } else if (path.equals("/api/payments") && integration.getStatus() != IntegrationStatus.CONNECTED) {
            throw failure(AuthErrorCode.MERCHANT_CONNECTION_REQUIRED);
        }
    }

    private MerchantAuthenticationException failure(AuthErrorCode code) {
        return new MerchantAuthenticationException(code);
    }
}
