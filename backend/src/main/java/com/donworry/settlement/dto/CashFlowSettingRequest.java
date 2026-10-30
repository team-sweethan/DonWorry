package com.donworry.settlement.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CashFlowSettingRequest(
        @NotNull @Min(0) @Max(9007199254740991L) Long openingBalance,
        @NotNull LocalDate baseDate) {}
