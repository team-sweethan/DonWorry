package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.security.MerchantAuthenticationException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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

    // 개발 로컬 설정으로 암복호화하고 서버를 다시 만들어도 같은 키로 읽는다
    @Test
    void encrypt_whenLocalMasterConfigured_roundTripsAcrossInstances() {
        var configuration = new MockEnvironment().withProperty("donworry.integration.local-master-keys.V1", master);
        var local = new MerchantKeyCipher("V1", name -> null, configuration);
        byte[] nonce = local.newNonce(), secret = local.newSecret(), encrypted = local.encrypt(1L, 1, secret, nonce);
        var restarted = new MerchantKeyCipher("V2", name -> null, configuration);
        assertThat(restarted.decrypt(1L, 1, "V1", encrypted, nonce)).isEqualTo(secret);
    }

    // prod가 다른 프로필과 함께 활성화돼도 YAML·Spring 속성으로 운영 키를 대신하지 않는다
    @Test
    void encrypt_whenProdActive_ignoresLocalAndSpringPropertyKeys() {
        var configuration = new MockEnvironment()
                .withProperty("donworry.integration.local-master-keys.V1", master)
                .withProperty("DONWORRY_HMAC_MASTER_KEY_V1", master);
        configuration.setActiveProfiles("local", "prod");
        var production = new MerchantKeyCipher("V1", name -> null, configuration);
        assertThatThrownBy(() -> production.encrypt(1L, 1, new byte[32], new byte[12]))
                .isInstanceOf(MerchantAuthenticationException.class)
                .hasMessageNotContaining(master);
    }

    // 환경변수가 있으면 로컬 값보다 우선하며 잘못된 환경변수도 로컬 값으로 숨기지 않는다
    @Test
    void encrypt_whenEnvironmentPresent_takesPrecedenceWithoutInvalidFallback() {
        byte[] other = new byte[32];
        Arrays.fill(other, (byte) 5);
        var configuration = new MockEnvironment()
                .withProperty(
                        "donworry.integration.local-master-keys.V1",
                        Base64.getEncoder().encodeToString(other));
        for (String profile : new String[] {"local", "prod"}) {
            configuration.setActiveProfiles(profile);
            var configured = new MerchantKeyCipher("V1", name -> master, configuration);
            byte[] nonce = configured.newNonce(), secret = configured.newSecret();
            assertThat(cipher.decrypt(1L, 1, "V1", configured.encrypt(1L, 1, secret, nonce), nonce))
                    .isEqualTo(secret);
        }
        configuration.setActiveProfiles("local");
        var invalid = new MerchantKeyCipher("V1", name -> "invalid", configuration);
        assertThatThrownBy(() -> invalid.encrypt(1L, 1, new byte[32], new byte[12]))
                .isInstanceOf(MerchantAuthenticationException.class);
    }

    // 개발 환경에서도 누락·잘못된 로컬 키를 자동 생성하거나 기본값으로 대체하지 않는다
    @Test
    void encrypt_whenLocalMasterMissingOrInvalid_failsClosed() {
        for (String value : new String[] {"", "invalid", master.substring(1)}) {
            var configuration = new MockEnvironment().withProperty("donworry.integration.local-master-keys.V1", value);
            var local = new MerchantKeyCipher("V1", name -> null, configuration);
            assertThatThrownBy(() -> local.encrypt(1L, 1, new byte[32], new byte[12]))
                    .isInstanceOf(MerchantAuthenticationException.class);
        }
        var missing = new MerchantKeyCipher("V1", name -> null, new MockEnvironment());
        assertThatThrownBy(() -> missing.encrypt(1L, 1, new byte[32], new byte[12]))
                .isInstanceOf(MerchantAuthenticationException.class);
    }
}
