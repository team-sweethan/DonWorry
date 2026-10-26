package com.donworry.payment.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PaymentOperationEvaluationResponse(
        String recordStatus,
        UUID evaluationRequestId,
        Long evaluationId,
        String evaluationResult,
        Integer riskScore,
        String errorCode,
        Instant evaluatedAt) {}
