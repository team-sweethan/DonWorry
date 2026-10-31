package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.security.MerchantAuthenticationException;
import java.util.*;
import org.junit.jupiter.api.Test;

class MerchantKeyCipherTests {
    private final String master = Base64.getEncoder().encodeToString(new byte[32]);
    private final MerchantKeyCipher cipher = new MerchantKeyCipher("V1", name -> master);
    // 암호화마다 새 IV를 쓰며 원래 키를 정확하게 복호화한다
    @Test
    void encrypt_whenRepeated_usesDistinctNoncesAndRoundTrips() {
        byte[] secret = cipher.newSecret(), a = cipher.newNonce(), b = cipher.newNonce();
        assertThat(a).isNotEqualTo(b);
        byte[] encrypted = cipher.encrypt(1L, 1, secret, a);
        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(cipher.decrypt(1L, 1, "V1", encrypted, a)).isEqualTo(secret);
        assertThat(cipher.encrypt(1L, 1, secret, b)).isNotEqualTo(encrypted);
    }
    // 암호문·가맹점·키 버전이 바뀌면 인증 태그가 복호화를 차단한다
    @Test
    void decrypt_whenTamperedOrMoved_rejectsWithoutLeaking() {
        byte[] nonce = cipher.newNonce(), encrypted = cipher.encrypt(1L, 1, cipher.newSecret(), nonce);
        assertThatThrownBy(() -> cipher.decrypt(2L, 1, "V1", encrypted, nonce))
                .isInstanceOf(MerchantAuthenticationException.class);
        assertThatThrownBy(() -> cipher.decrypt(1L, 2, "V1", encrypted, nonce))
                .isInstanceOf(MerchantAuthenticationException.class);
        encrypted[0] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(1L, 1, "V1", encrypted, nonce))
                .isInstanceOf(MerchantAuthenticationException.class)
                .hasMessageNotContaining(master);
    }
    // 마스터 키 누락·길이·식별자 오류를 기본 키로 대체하지 않는다
    @Test
    void encrypt_whenMasterInvalid_failsClosed() {
        for (String value : new String[] {"", "invalid", Base64.getEncoder().encodeToString(new byte[31])}) {
            var invalid = new MerchantKeyCipher("V1", name -> value);
            assertThatThrownBy(() -> invalid.encrypt(1L, 1, new byte[32], new byte[12]))
                    .isInstanceOf(MerchantAuthenticationException.class);
        }
        assertThatThrownBy(() -> new MerchantKeyCipher("V1", name -> null).encrypt(1L, 1, new byte[32], new byte[12]))
                .isInstanceOf(MerchantAuthenticationException.class);
        assertThatThrownBy(
                        () -> new MerchantKeyCipher("../V1", name -> master).encrypt(1L, 1, new byte[32], new byte[12]))
                .isInstanceOf(MerchantAuthenticationException.class);
    }
    // 새 활성 키로 전환해도 보관된 ID의 옛 키로 이전 데이터를 복호화한다
    @Test
    void decrypt_whenActiveKeyChanged_usesStoredKeyId() {
        byte[] nonce = cipher.newNonce(), secret = cipher.newSecret(), encrypted = cipher.encrypt(1L, 1, secret, nonce);
        byte[] nextMaster = new byte[32];
        java.util.Arrays.fill(nextMaster, (byte) 1);
        var rotated = new MerchantKeyCipher(
                "V2", name -> name.endsWith("V1") ? master : Base64.getEncoder().encodeToString(nextMaster));
        assertThat(rotated.decrypt(1L, 1, "V1", encrypted, nonce)).isEqualTo(secret);
    }
}
