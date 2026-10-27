package com.donworry.identity.service;

import com.donworry.identity.api.MerchantIdentity;
import com.donworry.identity.dto.MerchantSignedRequest;
import com.donworry.identity.entity.MerchantStatus;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.MerchantAuthenticationException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MerchantHmacService {
    private final MerchantRepository merchants;
    private final MerchantSecretService secrets;
    private final MerchantNonceStore nonces;
    private final Clock identityClock;

    public MerchantIdentity authenticate(MerchantSignedRequest input) {
        if (!matches(input.merchantCode(), "[A-Z0-9]{3,40}")
                || !matches(input.timestamp(), "[0-9]{10}")
                || !matches(input.nonce(), "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                || !matches(input.signature(), "[0-9a-f]{64}")) reject();
        if (input.target() == null
                || input.target().length() > 8192
                || input.target().indexOf('\n') >= 0
                || input.target().indexOf('\r') >= 0) reject();
        long timestamp = Long.parseLong(input.timestamp());
        long now = identityClock.instant().getEpochSecond();
        // 정수 초 경계를 포함해 과거·미래에 동일한 시간 허용 범위를 적용한다.
        if (timestamp < now - 300 || timestamp > now + 300) reject();
        var merchant = merchants
                .findByMerchantCode(input.merchantCode())
                .orElseThrow(() -> new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED));
        // DB collation이 대소문자를 무시하더라도 공개 코드의 별칭은 허용하지 않는다.
        if (!input.merchantCode().equals(merchant.getMerchantCode())) reject();
        byte[] expected = MerchantHmacSigner.sign(secrets.resolve(merchant.getSecretReference()), input);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(input.signature()))) reject();
        // 비활성 가맹점도 유효한 서명으로 자기 결제 결과를 조회할 수 있다.
        if ("POST".equals(input.method()) && merchant.getStatus() != MerchantStatus.ACTIVE)
            throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_INACTIVE);
        // 미래 시각의 서명도 최초 수신 후 만료까지 재사용되지 않도록 서명 시각을 기준으로 보관한다.
        if (!nonces.consume(
                merchant.getMerchantId(), UUID.fromString(input.nonce()), Instant.ofEpochSecond(timestamp + 301)))
            reject();
        return new MerchantIdentity(merchant.getMerchantId());
    }

    private boolean matches(String value, String regex) {
        return value != null && value.matches(regex);
    }

    private void reject() {
        throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED);
    }
}
