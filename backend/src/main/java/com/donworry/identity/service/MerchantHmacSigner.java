package com.donworry.identity.service;

import com.donworry.identity.dto.MerchantSignedRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MerchantHmacSigner {
    private MerchantHmacSigner() {}

    public static byte[] sign(byte[] key, MerchantSignedRequest request) {
        try {
            String hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(request.body()));
            String canonical = String.join(
                    "\n",
                    "DONWORRY-HMAC-V1",
                    request.merchantCode(),
                    request.timestamp(),
                    request.nonce(),
                    request.method(),
                    request.target(),
                    request.contentType(),
                    request.idempotencyKey(),
                    hash);
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
