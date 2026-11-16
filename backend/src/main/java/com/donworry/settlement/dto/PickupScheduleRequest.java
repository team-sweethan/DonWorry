package com.donworry.settlement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PickupScheduleRequest(@NotEmpty List<@NotNull @Valid PickupOrderItemRequest> items) {}
