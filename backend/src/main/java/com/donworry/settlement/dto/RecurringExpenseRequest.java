package com.donworry.settlement.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record RecurringExpenseRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull @Positive @Max(9007199254740991L) Long amount,
        @NotBlank String category,
        @Size(max = 500) String memo,
        @NotBlank String frequency,
        @NotNull LocalDate startDate,
        LocalDate endDate) {}
