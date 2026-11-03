package com.donworry.payment.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
public record PaymentOperationsPageResponse(
        List<PaymentOperationSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean hasNext,
        Instant observedAt) {
    public PaymentOperationsPageResponse {
        items = List.copyOf(items);
    }
}
