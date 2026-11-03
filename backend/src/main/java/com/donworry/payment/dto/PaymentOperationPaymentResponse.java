package com.donworry.payment.dto;

import java.time.Instant;
import lombok.Builder;

@Builder
public record PaymentOperationPaymentResponse(
        Long paymentId,
        Long merchantId,
        String orderRef,
        Long amount,
        String paymentStatus,
        Instant createdAt,
        Instant statusChangedAt,
        boolean hasAuthorization,
        String declineCode) {}
