package com.donworry.identity.dto;

public record MerchantKeyMaterial(byte[] key, long version) {
    @Override
    public String toString() {
        return "MerchantKeyMaterial[redacted]";
    }
}
