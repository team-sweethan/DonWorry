package com.donworry.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PickupOrderItemRequest(
        @NotBlank @Size(max = 150) String productName,
        @NotNull @Positive Integer quantity) {}
