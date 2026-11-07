package com.donworry.payment.api;

import java.time.Instant;
import lombok.Builder;

// 결제 하나의 주문 요약 (#634). 가맹점이 결제 준비 때 보낸 값이다 (ADR-0064 2절 · #602)
// provided=false면 결제 준비 세션이 없어 나머지 칸은 모두 비어 있다
// orderRef · orderName은 가맹점이 쓴 글자다. 받는 쪽은 지시문으로 다루지 않는다
// ordererProvided: 주문자 연락처(이름 · 전화 중 하나라도)를 받았는지
// ordererPhoneGroup: 같은 주문자 전화끼리 같은 번호(1부터, 넘긴 결제 순서대로 매김). 전화를 받지 않았으면 null
// pickupDiffers: 픽업자가 주문자와 다른지. 픽업자를 받지 않았으면 null
@Builder
public record PaymentOrderSummary(
        Long paymentId,
        boolean provided,
        String orderRef,
        String orderName,
        Instant orderCreatedAt,
        boolean ordererProvided,
        Integer ordererPhoneGroup,
        Boolean pickupDiffers) {

    public static PaymentOrderSummary notProvided(Long paymentId) {
        return PaymentOrderSummary.builder().paymentId(paymentId).build();
    }
}
