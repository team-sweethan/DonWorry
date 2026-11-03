package com.donworry.identity.dto;

import jakarta.validation.constraints.*;

public final class IntegrationRequests {
    private IntegrationRequests() {}

    public record KeyRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotNull @Min(0) Long expectedKeyVersion) {
        @Override
        public String toString() {
            return "KeyRequest[redacted]";
        }
    }

    public record RevokeRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotNull @Min(0) Long expectedKeyVersion) {}
}
