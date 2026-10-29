package com.donworry.identity.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;
import lombok.Builder;

public final class MerchantApplicationRequests {
    private MerchantApplicationRequests() {}

    public static final String CONSENT_VERSION = "2026-10-29";

    @Builder
    public record SubmitRequest(
            @NotBlank @Size(max = 100) String merchantName,
            @NotNull @Pattern(regexp = "[0-9]{10}") String businessRegistrationNumber,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Pattern(regexp = "[0-9]{9,15}") String phone,
            @Size(max = 1000) String inquiry,
            @NotNull @AssertTrue Boolean consent,
            @NotNull @Pattern(regexp = "2026-10-29") String consentVersion) {
        public SubmitRequest {
            merchantName = trim(merchantName);
            businessRegistrationNumber = digits(businessRegistrationNumber);
            contactName = trim(contactName);
            email = normalizeEmail(email);
            phone = digits(phone);
            inquiry = trim(inquiry);
        }
    }

    public record VerifyRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override
        public String toString() {
            return "VerifyRequest[redacted]";
        }
    }

    public record ResendRequest(
            @NotBlank @Email @Size(max = 254) String email) {
        public ResendRequest {
            email = normalizeEmail(email);
        }
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String digits(String value) {
        return value == null ? null : value.replaceAll("[- ]", "");
    }
}
