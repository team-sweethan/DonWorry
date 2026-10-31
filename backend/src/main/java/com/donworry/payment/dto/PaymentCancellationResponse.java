package com.donworry.payment.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PaymentCancellationResponse(
        UUID requestKey,
        Long paymentId,
        String requestStatus,
        Long cancellationId,
        String operation,
        Long amount,
        Instant requestedAt,
        Instant updatedAt,
        Instant completedAt,
        String code,
        String nextAction) {}
