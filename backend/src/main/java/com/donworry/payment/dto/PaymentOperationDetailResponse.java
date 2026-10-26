package com.donworry.payment.dto;

import lombok.Builder;

@Builder
public record PaymentOperationDetailResponse(
        PaymentOperationPaymentResponse payment,
        PaymentOperationRequestResponse request,
        PaymentOperationEvaluationResponse evaluation,
        PaymentOperationActionResponse action,
        PaymentOperationObservationResponse observation) {}
