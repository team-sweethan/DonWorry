package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentStatus;
import lombok.Builder;

@Builder
public record PaymentAuthorizationResult(
        Long paymentId,
        PaymentEvaluationResult evaluationResult,
        PaymentStatus status,
        String authorizationRef,
        PaymentRules.NextStep nextStep,
        String declineCode) {}
