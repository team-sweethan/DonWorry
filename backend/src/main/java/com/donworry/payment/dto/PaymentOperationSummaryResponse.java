package com.donworry.payment.dto;

import java.time.Instant;
import lombok.Builder;

@Builder
public record PaymentOperationSummaryResponse(
        Long paymentId,
        Long merchantId,
        Long amount,
        String orderRef,
        String paymentStatus,
        Instant createdAt,
        String actionType,
        String actionStatus,
        PaymentOperationObservationResponse observation) {}
