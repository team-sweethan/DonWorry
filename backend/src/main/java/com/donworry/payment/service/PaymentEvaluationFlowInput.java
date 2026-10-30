package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentRiskSignals;
import lombok.Builder;

@Builder
public record PaymentEvaluationFlowInput(
        Long merchantId, String customerRef, String orderRef, Long amount, PaymentRiskSignals riskSignals) {

    public PaymentEvaluationFlowInput {
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        requireReference(customerRef, "customerRef");
        requireReference(orderRef, "orderRef");
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        riskSignals = PaymentRiskSignals.orNone(riskSignals);
    }

    private static void requireReference(String value, String name) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 100) {
            throw new IllegalArgumentException(name + " must be nonblank and at most 100 characters");
        }
    }
}
