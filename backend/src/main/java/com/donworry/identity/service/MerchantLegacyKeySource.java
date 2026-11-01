package com.donworry.identity.service;

import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class MerchantLegacyKeySource {
    private final java.util.function.Function<String, String> environment;

    @org.springframework.beans.factory.annotation.Autowired
    public MerchantLegacyKeySource() {
        this(System::getenv);
    }

    MerchantLegacyKeySource(java.util.function.Function<String, String> environment) {
        this.environment = environment;
    }

    public byte[] resolve(String reference) {
        try {
            if (reference == null || !reference.matches("DONWORRY_MERCHANT_HMAC_[A-Z0-9_]{1,150}"))
                throw new IllegalArgumentException();
            String encoded = environment.apply(reference);
            if (encoded == null || encoded.length() > 512) throw new IllegalArgumentException();
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length < 32) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Legacy merchant key configuration unavailable");
        }
    }
}
