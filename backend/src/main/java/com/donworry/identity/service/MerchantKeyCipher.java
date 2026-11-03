package com.donworry.identity.service;

import com.donworry.identity.security.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class MerchantKeyCipher {
    private final String activeId;
    private final Function<String, String> environment;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public MerchantKeyCipher(
            @Value("${donworry.integration.active-master-key-id:V1}") String activeId, Environment configuration) {
        this(activeId, System::getenv, configuration);
    }

    MerchantKeyCipher(String activeId, Function<String, String> environment, Environment configuration) {
        this(activeId, name -> {
            String value = environment.apply(name);
            // prod가 다른 프로필과 함께 켜져도 로컬 설정의 비밀값은 사용하지 않는다.
            if (value != null || configuration.matchesProfiles("prod")) return value;
            return configuration.getProperty(
                    "donworry.integration.local-master-keys." + name.substring("DONWORRY_HMAC_MASTER_KEY_".length()));
        });
    }

    MerchantKeyCipher(String activeId, Function<String, String> environment) {
        this.activeId = activeId;
        this.environment = environment;
    }

    public String activeId() {
        return activeId;
    }

    public byte[] newSecret() {
        var secret = new byte[32];
        random.nextBytes(secret);
        return secret;
    }

    public byte[] newNonce() {
        var nonce = new byte[12];
        random.nextBytes(nonce);
        return nonce;
    }

    public byte[] encrypt(Long merchantId, long version, byte[] secret, byte[] nonce) {
        return crypt(Cipher.ENCRYPT_MODE, merchantId, version, activeId, secret, nonce);
    }

    public byte[] decrypt(Long merchantId, long version, String id, byte[] ciphertext, byte[] nonce) {
        return crypt(Cipher.DECRYPT_MODE, merchantId, version, id, ciphertext, nonce);
    }

    private byte[] crypt(int mode, Long merchantId, long version, String id, byte[] input, byte[] nonce) {
        try {
            if (id == null || !id.matches("[A-Z0-9_]{1,40}") || nonce == null || nonce.length != 12)
                throw new IllegalArgumentException();
            String encoded = environment.apply("DONWORRY_HMAC_MASTER_KEY_" + id);
            if (encoded == null || encoded.length() > 128) throw new IllegalArgumentException();
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("DONWORRY-KEY-V1\n" + merchantId + "\n" + version).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE);
        }
    }
}
