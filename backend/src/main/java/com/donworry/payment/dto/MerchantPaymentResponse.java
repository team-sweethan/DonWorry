package com.donworry.payment.dto;

import com.donworry.payment.entity.PaymentStatus;
import java.time.Instant;
import lombok.Builder;

// 고객 참조·승인 참조·평가 정보·내부 처리 단계는 외부 결과에 포함하지 않는다.
// authorized: 카드 승인이 이뤄졌는지(승인 참조가 있는지, 이후 취소돼도 true). PENDING은 승인 전 평가 중·오류와
// 승인 후 매입 대기를 함께 담으므로 가맹점은 이 값으로 결제됨을 판단한다. 승인 참조 원문은 내보내지 않는다 (ADR-0063 3-1).
@Builder
public record MerchantPaymentResponse(
        Long paymentId,
        String orderRef,
        Long attemptNo,
        PaymentStatus paymentStatus,
        Instant requestedAt,
        Instant statusChangedAt,
        Long amount,
        String declineCode,
        boolean authorized) {}
