package com.donworry.identity.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

public final class ApplicationReviewRequests {
    private ApplicationReviewRequests() {}

    @Builder
    public record ApproveRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9]{3,40}") String merchantCode,

            @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 6)
            BigDecimal pgFeeRate,

            @NotNull @Size(max = 2) List<@NotNull @Pattern(regexp = "FDS_VIEW|FINANCE_VIEW") String> permissions) {}

    public record ResendRequest(@NotNull @Positive Long expectedDeliveryId) {}

    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record SetupRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotNull @Size(max = 72) String newPassword) {
        @Override
        public String toString() {
            return "SetupRequest[redacted]";
        }
    }
}
