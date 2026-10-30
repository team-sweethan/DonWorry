package com.donworry.identity.dto;

import com.ibm.icu.text.IDNA;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import lombok.Builder;

public final class MerchantApplicationRequests {
    private MerchantApplicationRequests() {}

    private static final IDNA SHOP_URL_IDNA = IDNA.getUTS46Instance(
            IDNA.USE_STD3_RULES | IDNA.CHECK_BIDI | IDNA.CHECK_CONTEXTJ | IDNA.NONTRANSITIONAL_TO_ASCII);

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
                var uri = new URI(shopUrl);
                String authority = uri.getRawAuthority();
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || authority == null
                        || authority.contains("@")) return false;
                if (uri.getHost() != null) return true;
                // 브라우저와 같은 UTS #46으로 검증하고 원문은 변환하지 않는다.
                int separator = authority.lastIndexOf(':');
                String host = separator < 0 ? authority : authority.substring(0, separator);
                String port = separator < 0 ? "" : authority.substring(separator);
                var info = new IDNA.Info();
                var asciiHost = SHOP_URL_IDNA.nameToASCII(host, new StringBuilder(), info);
                // WHATWG 비엄격 처리는 구간 3·4번째 하이픈과 DNS 길이를 오류로 취급하지 않는다.
                if (info.getErrors().stream()
                        .anyMatch(error -> error != IDNA.Error.HYPHEN_3_4
                                && error != IDNA.Error.LABEL_TOO_LONG
                                && error != IDNA.Error.DOMAIN_NAME_TOO_LONG)) return false;
                return new URI(uri.getScheme() + "://" + asciiHost + port).getHost() != null;
            } catch (URISyntaxException | IllegalArgumentException e) {
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
