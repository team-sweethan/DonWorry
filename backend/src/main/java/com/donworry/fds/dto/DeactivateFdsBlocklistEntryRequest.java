package com.donworry.fds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeactivateFdsBlocklistEntryRequest(
        @NotBlank @Size(max = 500) String reason) {}
