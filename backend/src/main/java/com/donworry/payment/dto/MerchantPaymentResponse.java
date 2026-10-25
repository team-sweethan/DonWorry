package com.donworry.payment.dto;

import com.donworry.payment.entity.PaymentStatus;
import java.time.Instant;
import lombok.Builder;

// 고객 참조·승인 참조·평가 정보·내부 처리 단계는 외부 결과에 포함하지 않는다.
@Builder
public record MerchantPaymentResponse(
        Long paymentId,
        String orderRef,
        Long attemptNo,
        PaymentStatus paymentStatus,
        Instant requestedAt,
        Instant statusChangedAt,
        Long amount,
        String declineCode) {}
