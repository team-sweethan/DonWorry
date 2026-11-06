package com.donworry.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.Builder;

// 가맹점 서버의 결제 준비 요청. 금액·주문번호·고객 참조·주문 시각 규칙은 POST /api/payments와 같다.
// 카드·기기·IP는 받지 않는다. 결제창이 구매자에게서 직접 받는다.
// 주문자 · 픽업자 4칸은 선택값이다 (ADR-0082, #602). 서버는 암호문으로만 저장하고 픽업자 원문은 저장하지 않는다.
@Builder
public record PaymentSessionCreateRequest(
        @NotBlank @Size(max = 100) String orderRef,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String customerRef,
        @NotNull @Positive Long amount,
        @NotNull @PastOrPresent Instant orderCreatedAt,
        @NotBlank @Size(max = 100) String orderName,
        @Size(max = 254) String buyerEmail,
        @NotBlank @Size(max = 500) String successUrl,
        @NotBlank @Size(max = 500) String failUrl,
        @Size(max = 500) String notifyUrl,
        @Size(max = 50) String ordererName,
        @Size(max = 30) String ordererPhone,
        @Size(max = 50) String pickupName,
        @Size(max = 30) String pickupPhone) {

    // 기본 toString은 모든 칸을 찍는다. 이메일 · 이름 · 전화가 로그에 남지 않게 뺀다
    @Override
    public String toString() {
        return "PaymentSessionCreateRequest[orderRef=" + orderRef + ", customerRef=" + customerRef + ", amount="
                + amount + ", orderCreatedAt=" + orderCreatedAt + ", 연락처 생략]";
    }
}
