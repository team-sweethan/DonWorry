package com.donworry.payment.api;

import java.util.UUID;
import lombok.Builder;

@Builder
public record PaymentActionCommand(Long paymentId, Long decisionId, String actionType, UUID idempotencyKey) {
    public PaymentActionCommand {
        if (paymentId == null
                || paymentId <= 0
                || decisionId == null
                || decisionId <= 0
                || !("CAPTURE".equals(actionType) || "VOID".equals(actionType))
                || idempotencyKey == null) {
            throw new IllegalArgumentException("유효한 결제·판정 ID, CAPTURE/VOID, 멱등 키가 필요합니다");
        }
    }
}
