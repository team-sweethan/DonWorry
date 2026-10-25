package com.donworry.identity.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Builder;

public final class AdminAccountRequests {
    private AdminAccountRequests() {}

    @Builder
    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9_]{3,30}") String loginId,
            @NotBlank @Size(max = 100) String displayName,

            @NotNull @Pattern(regexp = "OPERATOR|SECURITY_STAFF")
            String role,

            @Positive Long merchantId) {}

    public record SetPermissionsRequest(
            @NotNull @Size(max = 2) List<@NotNull @Pattern(regexp = "FDS_VIEW|FINANCE_VIEW") String> permissions) {}

    public record SetStatusRequest(
            @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status) {}
}
