package com.donworry.payment.dto;

import java.util.UUID;
import lombok.Builder;

@Builder
public record PaymentOperationRequestResponse(
        String recordStatus, Long requestId, Long attemptNo, UUID requestIdempotencyKey, String processingStage) {}
