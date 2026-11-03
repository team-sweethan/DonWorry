package com.donworry.payment.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PaymentOperationActionResponse(
        String recordStatus,
        Long actionId,
        Long decisionId,
        String actionType,
        UUID actionIdempotencyKey,
        String actionStatus,
        String errorCode,
        Instant requestedAt,
        Instant completedAt,
        Long captureId) {}
