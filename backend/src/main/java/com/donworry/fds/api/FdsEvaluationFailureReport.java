package com.donworry.fds.api;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record FdsEvaluationFailureReport(
        UUID failureReportId,
        UUID originalEvaluationRequestId,
        UUID evaluationRequestId,
        Long evaluationId,
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
        String buyerEmail,
        FdsResult evaluationResult,
        String errorCode,
        String sanitizedErrorMessage,
        Instant requestedAt,
        Instant respondedAt,
        String paymentStatus,
        String processingStage,
        Long requestId,
        UUID idempotencyKey,
        String orderRef,
        String correlationId,
        int evaluationAttemptNo) {

    public FdsEvaluationFailureReport {
        require(failureReportId, "failureReportId");
        require(originalEvaluationRequestId, "originalEvaluationRequestId");
        require(evaluationRequestId, "evaluationRequestId");
        requirePositive(paymentId, "paymentId");
        requirePositive(merchantId, "merchantId");
        requireText(customerRef, "customerRef");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        require(paymentRequestedAt, "paymentRequestedAt");
        require(requestedAt, "requestedAt");
        requireText(paymentStatus, "paymentStatus");
        requireText(processingStage, "processingStage");
        requirePositive(requestId, "requestId");
        require(idempotencyKey, "idempotencyKey");
        if (evaluationAttemptNo <= 0) throw new IllegalArgumentException("evaluationAttemptNo must be positive");
        if (evaluationId == null || evaluationId <= 0) {
            throw new IllegalArgumentException("evaluationId must be positive");
        }
        if (evaluationResult != FdsResult.ERROR || errorCode == null || errorCode.isBlank() || respondedAt == null) {
            throw new IllegalArgumentException("internal FDS error requires ERROR result, errorCode, and respondedAt");
        }
        if (sanitizedErrorMessage != null && sanitizedErrorMessage.length() > 500) {
            throw new IllegalArgumentException("sanitizedErrorMessage must be at most 500 characters");
        }
        var evaluationRequest = new FdsEvaluationRequest(
                evaluationRequestId,
                paymentId,
                merchantId,
                customerRef,
                amount,
                paymentRequestedAt,
                deviceId,
                cardToken,
                cardDisplayLabel,
                ipCountryCode,
                ipAddress,
                buyerEmail);
        deviceId = evaluationRequest.deviceId();
        cardToken = evaluationRequest.cardToken();
        cardDisplayLabel = evaluationRequest.cardDisplayLabel();
        ipCountryCode = evaluationRequest.ipCountryCode();
        ipAddress = evaluationRequest.ipAddress();
        buyerEmail = evaluationRequest.buyerEmail();
    }

    private static void require(Object value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
