package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.security.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MerchantSecretServiceTests {
    private final MerchantSecretService service = new MerchantSecretService();

    // 임의의 설정 이름이나 잘못된 키는 기본 키로 대체하지 않는다
    @Test
    void resolve_whenReferenceInvalid_failsClosed() {
        assertThatThrownBy(() -> service.resolve(null))
                .isInstanceOfSatisfying(
                        MerchantAuthenticationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED));
        for (String reference : new String[] {"HOME", "spring.datasource.password", "DONWORRY_MERCHANT_HMAC_"}) {
            assertThatThrownBy(() -> service.resolve(reference))
                    .isInstanceOfSatisfying(
                            MerchantAuthenticationException.class,
                            e -> assertThat(e.getErrorCode())
                                    .isEqualTo(AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE));
        }
    }

    // Base64 키는 32바이트 이상이어야 하며 오류 메시지에 입력값을 넣지 않는다
    @Test
    void decode_whenMalformedOrTooShort_rejectsWithoutLeaking() {
        for (String value :
                new String[] {"not base64 secret", Base64.getEncoder().encodeToString(new byte[31])}) {
            assertThatThrownBy(() -> service.decode(value))
                    .isInstanceOf(MerchantAuthenticationException.class)
                    .hasMessageNotContaining(value);
        }
        assertThat(service.decode(Base64.getEncoder().encodeToString(new byte[32])))
                .hasSize(32);
    }
}
