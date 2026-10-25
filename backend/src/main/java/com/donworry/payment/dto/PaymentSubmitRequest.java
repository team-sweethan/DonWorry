package com.donworry.payment.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import lombok.Builder;

@Builder
public record PaymentSubmitRequest(
        @NotBlank @Size(max = 100) String orderRef,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String customerRef,
        @NotNull @Positive Long amount,
        @NotNull @PastOrPresent Instant orderCreatedAt) {}
