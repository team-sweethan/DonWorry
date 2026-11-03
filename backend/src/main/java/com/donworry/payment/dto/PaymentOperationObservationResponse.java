package com.donworry.payment.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
public record PaymentOperationObservationResponse(
        PaymentOperationCategory category,
        List<String> reasons,
        List<String> limitations,
        Long elapsedSeconds,
        String waitingBasis,
        Instant waitingSince,
        Long waitingSeconds,
        Instant observedAt) {
    public PaymentOperationObservationResponse {
        reasons = List.copyOf(reasons);
        limitations = List.copyOf(limitations);
    }
}
