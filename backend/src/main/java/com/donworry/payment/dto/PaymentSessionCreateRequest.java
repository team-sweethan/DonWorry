package com.donworry.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

// 가맹점 서버의 결제 준비 요청. 금액·주문번호·고객 참조·주문 시각 규칙은 POST /api/payments와 같다.
// 카드·기기·IP는 받지 않는다. 결제창이 구매자에게서 직접 받는다.
public record PaymentSessionCreateRequest(
        @NotBlank @Size(max = 100) String orderRef,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String customerRef,
        @NotNull @Positive Long amount,
        @NotNull @PastOrPresent Instant orderCreatedAt,
        @NotBlank @Size(max = 100) String orderName,
        @Size(max = 254) String buyerEmail,
        @NotBlank @Size(max = 500) String successUrl,
        @NotBlank @Size(max = 500) String failUrl,
        @Size(max = 500) String notifyUrl) {}
