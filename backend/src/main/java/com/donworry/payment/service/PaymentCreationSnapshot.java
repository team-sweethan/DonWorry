package com.donworry.payment.service;

import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import java.time.Instant;
import lombok.Builder;

@Builder
public record PaymentCreationSnapshot(
        Long paymentId,
        String customerRef,
        Long amount,
        Instant createdAt,
        PaymentStatus status,
        String authorizationRef) {

    static PaymentCreationSnapshot from(Payment payment) {
        return PaymentCreationSnapshot.builder()
                .paymentId(payment.getPaymentId())
                .customerRef(payment.getCustomerRef())
                .amount(payment.getAmount())
                .createdAt(payment.getCreatedAt())
                .status(payment.getStatus())
                .authorizationRef(payment.getAuthorizationRef())
                .build();
    }
}
