package com.donworry.identity.dto;

public record IntegrationKeyResponse(String secret, long keyVersion) {
    @Override
    public String toString() {
        return "IntegrationKeyResponse[redacted]";
    }
}
