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
        String ipCountryCode) {

    public FdsEvaluationRequest(
            UUID evaluationRequestId,
            Long paymentId,
            Long merchantId,
            String customerRef,
            long amount,
            Instant paymentRequestedAt) {
        this(evaluationRequestId, paymentId, merchantId, customerRef, amount, paymentRequestedAt, null, null, null);
    }

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
        if (ipCountryCode != null) {
            ipCountryCode = ipCountryCode.strip().toUpperCase(java.util.Locale.ROOT);
            if (!ipCountryCode.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("ipCountryCode must be an ISO alpha-2 code");
            }
        }
    }

    private static String optionalIdentifier(String value, String name, int maxLength) {
        if (value == null) return null;
        if (value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(name + " must be a trimmed identifier");
        }
        return value;
    }
}
