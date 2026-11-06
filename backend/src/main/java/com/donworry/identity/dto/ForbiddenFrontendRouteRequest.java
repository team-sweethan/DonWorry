package com.donworry.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForbiddenFrontendRouteRequest(
        @NotBlank @Size(max = 512) String path) {}
