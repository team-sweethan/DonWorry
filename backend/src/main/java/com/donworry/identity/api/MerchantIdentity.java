package com.donworry.identity.api;

public record MerchantIdentity(Long merchantId) {
    public MerchantIdentity {
        if (merchantId == null || merchantId <= 0) throw new IllegalArgumentException("invalid merchant identity");
    }
}
