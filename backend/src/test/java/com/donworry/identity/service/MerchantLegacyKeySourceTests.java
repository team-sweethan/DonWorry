package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class MerchantLegacyKeySourceTests {
    // 기존 환경변수 참조 검사 규칙은 이전 전용 경계에서도 유지한다
    @Test
    void resolve_whenReferenceInvalid_rejectsWithoutReadingEnvironment() {
        var source = new MerchantLegacyKeySource(name -> {
            throw new AssertionError("must not read environment");
        });
        for (String value : new String[] {null, "HOME", "spring.datasource.password", "DONWORRY_MERCHANT_HMAC_"}) {
            assertThatThrownBy(() -> source.resolve(value)).isInstanceOf(IllegalStateException.class);
        }
    }
    // Base64 누락·형식·길이 검사는 원문을 오류에 넣지 않고 거절한다
    @Test
    void resolve_whenMalformedOrTooShort_rejectsWithoutLeaking() {
        for (String value :
                new String[] {null, "invalid secret", Base64.getEncoder().encodeToString(new byte[31]), "x".repeat(513)
                }) {
            var source = new MerchantLegacyKeySource(name -> value);
            var error = catchThrowable(() -> source.resolve("DONWORRY_MERCHANT_HMAC_TEST"));
            assertThat(error).isInstanceOf(IllegalStateException.class);
            if (value != null) assertThat(error.getMessage()).doesNotContain(value);
            assertThat(error.getCause()).isNull();
        }
    }
    // 32바이트 이상인 기존 키를 길이에 관계없이 같은 바이트로 옮긴다
    @Test
    void resolve_whenValid_preservesExistingBytes() {
        for (int length : new int[] {32, 48, 64}) {
            byte[] key = new byte[length];
            java.util.Arrays.fill(key, (byte) 17);
            var source = new MerchantLegacyKeySource(name -> Base64.getEncoder().encodeToString(key));
            assertThat(source.resolve("DONWORRY_MERCHANT_HMAC_TEST")).isEqualTo(key);
        }
    }
}
