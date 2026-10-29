package com.donworry.settlement.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record PickupCompletionRequest(@NotNull Instant pickedUpAt) {}
