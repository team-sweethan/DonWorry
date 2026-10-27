package com.donworry.identity.service;

import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.MerchantAuthenticationException;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class MerchantSecretService {
    public byte[] resolve(String reference) {
        if (reference == null)
            throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED);
        if (!reference.matches("DONWORRY_MERCHANT_HMAC_[A-Z0-9_]{1,150}")) return unavailable();
        // Spring property/시스템 속성이 아니라 서버 환경변수에서만 키를 읽는다.
        return decode(System.getenv(reference));
    }

    byte[] decode(String encoded) {
        if (encoded == null || encoded.length() > 512) return unavailable();
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length < 32) return unavailable();
            return key;
        } catch (IllegalArgumentException exception) {
            return unavailable();
        }
    }

    private byte[] unavailable() {
        throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE);
    }
}
