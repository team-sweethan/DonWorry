package com.donworry.payment.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import lombok.Builder;

// 아래 6개 FDS 식별자는 선택값이다 (#325). 카드는 원문이 아니라 토큰과 마스킹 표시값만 받는다.
@Builder
public record PaymentSubmitRequest(
        @NotBlank @Size(max = 100) String orderRef,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String customerRef,
        @NotNull @Positive Long amount,
        @NotNull @PastOrPresent Instant orderCreatedAt,
        @Size(max = 100) String deviceId,
        @Size(max = 255) String cardToken,
        @Size(max = 50) String cardDisplayLabel,
        @Size(max = 2) String ipCountryCode,
        @Size(max = 45) String ipAddress,
        @Size(max = 254) String buyerEmail) {
    public PaymentSubmitRequest(String orderRef, String customerRef, Long amount, Instant orderCreatedAt) {
        this(orderRef, customerRef, amount, orderCreatedAt, null, null, null, null, null, null);
    }
}
