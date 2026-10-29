package com.donworry.fds.api;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record FdsReevaluationRequest(
        UUID originalEvaluationRequestId,
        UUID newEvaluationRequestId,
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
        UUID retryId) {

    public FdsReevaluationRequest {
        if (originalEvaluationRequestId == null || newEvaluationRequestId == null || retryId == null) {
            throw new IllegalArgumentException("evaluation request IDs and retryId must not be null");
        }
        if (originalEvaluationRequestId.equals(newEvaluationRequestId)) {
            throw new IllegalArgumentException("newEvaluationRequestId must differ from originalEvaluationRequestId");
        }
        var evaluationRequest = new FdsEvaluationRequest(
                newEvaluationRequestId,
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

    public FdsReevaluationRequest(
            UUID originalEvaluationRequestId,
            UUID newEvaluationRequestId,
            Long paymentId,
            Long merchantId,
            String customerRef,
            long amount,
            Instant paymentRequestedAt,
            String deviceId,
            String cardToken,
            String ipCountryCode,
            UUID retryId) {
        this(
                originalEvaluationRequestId,
                newEvaluationRequestId,
                paymentId,
                merchantId,
                customerRef,
                amount,
                paymentRequestedAt,
                deviceId,
                cardToken,
                null,
                ipCountryCode,
                null,
                null,
                retryId);
    }

    public FdsReevaluationRequest(
            UUID originalEvaluationRequestId,
            UUID newEvaluationRequestId,
            Long paymentId,
            Long merchantId,
            String customerRef,
            long amount,
            Instant paymentRequestedAt,
            UUID retryId) {
        this(
                originalEvaluationRequestId,
                newEvaluationRequestId,
                paymentId,
                merchantId,
                customerRef,
                amount,
                paymentRequestedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                retryId);
    }

    public FdsEvaluationRequest evaluationRequest() {
        return new FdsEvaluationRequest(
                newEvaluationRequestId,
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
    }
}
