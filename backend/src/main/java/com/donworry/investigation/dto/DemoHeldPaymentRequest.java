package com.donworry.investigation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

// 시연용 HELD 결제 — REVIEW 구간(50~79점, fds 정본 02 §2.2)만 받는다
public record DemoHeldPaymentRequest(
        @NotNull Long merchantId,
        @NotBlank String customerRef,
        @NotNull @Positive Long amount,
        @NotNull @Min(50) @Max(79) Integer riskScore,
        List<@NotBlank String> ruleHits) {}
