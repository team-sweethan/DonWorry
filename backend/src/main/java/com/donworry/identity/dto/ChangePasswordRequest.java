package com.donworry.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank String currentPassword, @NotBlank String newPassword) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=[REDACTED], newPassword=[REDACTED]]";
    }
}
