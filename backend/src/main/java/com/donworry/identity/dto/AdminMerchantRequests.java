package com.donworry.identity.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Builder;

public final class AdminMerchantRequests {
    private AdminMerchantRequests() {}

    @Builder
    public record CreateMerchantRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9]{3,40}") String merchantCode,
            @NotBlank @Size(max = 100) String displayName,

            @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 6)
            BigDecimal pgFeeRate) {}

    public record SetMerchantStatusRequest(
            @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}
}
