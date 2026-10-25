package com.donworry.payment.service;

import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.api.PaymentActionResult;
import com.donworry.payment.entity.PaymentAction;
import com.donworry.payment.entity.PaymentActionType;

final class PaymentActionMapping {
    private PaymentActionMapping() {}

    static PaymentActionReceipt receipt(PaymentAction action) {
        return switch (action.getStatus()) {
            case PENDING -> PaymentActionReceipt.pending();
            case FAILED -> PaymentActionReceipt.rejected(action.getErrorCode());
            case SUCCEEDED ->
                PaymentActionReceipt.accepted(
                        action.getActionType() == PaymentActionType.CAPTURE
                                ? action.getCaptureId()
                                : action.getActionId());
        };
    }

    static PaymentActionResult result(PaymentAction action) {
        var receipt = receipt(action);
        return PaymentActionResult.builder()
                .paymentId(action.getPaymentId())
                .decisionId(action.getDecisionId())
                .actionType(action.getActionType().name())
                .idempotencyKey(action.getIdempotencyKey())
                .status(receipt.status())
                .targetOperationId(receipt.targetOperationId())
                .errorCode(receipt.errorCode())
                .completedAt(action.getCompletedAt())
                .build();
    }
}
