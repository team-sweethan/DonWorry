package com.donworry.identity.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;
import lombok.Builder;

public final class MerchantApplicationRequests {
    private MerchantApplicationRequests() {}

    public static final String CONSENT_VERSION = "2026-10-30.1";

    @Builder
    public record SubmitRequest(
            @NotBlank @Size(max = 100) String merchantName,
            @NotBlank @Size(max = 2048) String shopUrl,
            @NotNull @Pattern(regexp = "[0-9]{10}") String businessRegistrationNumber,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Pattern(regexp = "[0-9]{9,15}") String phone,
            @NotBlank @Pattern(regexp = "[a-z0-9_]{3,30}") String requestedLoginId,
            @Size(max = 1000) String inquiry,
            @NotNull @AssertTrue Boolean consent,
            @NotNull @Pattern(regexp = "2026-10-30\\.1") String consentVersion) {
        public SubmitRequest {
            merchantName = trim(merchantName);
            shopUrl = trim(shopUrl);
            businessRegistrationNumber = digits(businessRegistrationNumber);
            contactName = trim(contactName);
            email = normalizeEmail(email);
            phone = digits(phone);
            requestedLoginId = trim(requestedLoginId);
            inquiry = trim(inquiry);
        }

        @AssertTrue(message = "쇼핑몰 주소는 사용자 정보가 없는 HTTP(S) 절대 URL이어야 합니다.")
        public boolean isShopUrlValid() {
            if (shopUrl == null || shopUrl.isBlank()) return true;
            try {
                var uri = new java.net.URI(shopUrl);
                return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null
                        && uri.getRawUserInfo() == null;
            } catch (java.net.URISyntaxException e) {
                return false;
            }
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
