package com.donworry.fds.api;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record FdsEvaluationRequest(
        UUID evaluationRequestId,
        Long paymentId,
        Long merchantId,
        String customerRef,
        long amount,
        Instant paymentRequestedAt,
        String deviceId,
        String cardToken,
        String cardDisplayLabel,
        String ipCountryCode,
        String ipAddress,
        String buyerEmail) {

    public FdsEvaluationRequest {
        if (evaluationRequestId == null) {
            throw new IllegalArgumentException("evaluationRequestId must not be null");
        }
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null");
        }
        if (paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("customerRef must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (paymentRequestedAt == null) {
            throw new IllegalArgumentException("paymentRequestedAt must not be null");
        }
        deviceId = optionalIdentifier(deviceId, "deviceId", 100);
        cardToken = optionalIdentifier(cardToken, "cardToken", 255);
        cardDisplayLabel = optionalIdentifier(cardDisplayLabel, "cardDisplayLabel", 50);
        ipCountryCode = optionalIdentifier(ipCountryCode, "ipCountryCode", 2);
        if (ipCountryCode != null) ipCountryCode = ipCountryCode.toUpperCase(java.util.Locale.ROOT);
        if (ipCountryCode != null && !ipCountryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("ipCountryCode must be an ISO alpha-2 code");
        }
        ipAddress = optionalIdentifier(ipAddress, "ipAddress", 45);
        buyerEmail = optionalIdentifier(buyerEmail, "buyerEmail", 254);
        if (buyerEmail != null) buyerEmail = buyerEmail.toLowerCase(java.util.Locale.ROOT);
    }

    public FdsEvaluationRequest requireRiskSignals() {
        required(deviceId, "deviceId");
        required(cardToken, "cardToken");
        required(cardDisplayLabel, "cardDisplayLabel");
        required(ipCountryCode, "ipCountryCode");
        required(ipAddress, "ipAddress");
        required(buyerEmail, "buyerEmail");
        return this;
    }

    private static String optionalIdentifier(String value, String name, int maxLength) {
        if (value == null) return null;
        if (value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(name + " must be a trimmed identifier");
        }
        return value;
    }

    private static void required(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
    }
}
