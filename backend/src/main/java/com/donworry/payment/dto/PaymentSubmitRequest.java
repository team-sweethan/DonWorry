package com.donworry.payment.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import lombok.Builder;

// 모든 신규 결제 요청은 FDS 평가에 필요한 식별자를 함께 보낸다. 카드는 원문이 아니라 토큰과 마스킹 표시값만 받는다.
@Builder
public record PaymentSubmitRequest(
        @NotBlank @Size(max = 100) String orderRef,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String customerRef,
        @NotNull @Positive Long amount,
        @NotNull @PastOrPresent Instant orderCreatedAt,
        @Size(max = 100) String deviceId,
        @Size(max = 255) String cardToken,
        @Size(max = 50) String cardDisplayLabel,
        @Pattern(regexp = "[A-Za-z]{2}") String ipCountryCode,
        @Size(max = 45) String ipAddress,
        @Email @Size(max = 254) String buyerEmail) {}
