package com.donworry.payment.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record MerchantPaymentOrderResponse(
        String orderRef,
        MerchantPaymentResponse latestPayment,
        List<MerchantPaymentResponse> attempts,
        Long nextBeforeAttemptNo) {
    public MerchantPaymentOrderResponse {
        attempts = List.copyOf(attempts);
    }
}
