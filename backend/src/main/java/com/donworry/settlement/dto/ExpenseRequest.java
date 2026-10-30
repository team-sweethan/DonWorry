package com.donworry.settlement.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record ExpenseRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull LocalDate expenseDate,
        @NotNull @Positive @Max(9007199254740991L) Long amount,
        @NotBlank String category,
        @Size(max = 500) String memo) {}
