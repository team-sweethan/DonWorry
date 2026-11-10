package com.donworry.settlement.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ExpenseCompletionRequest(@NotNull LocalDate paidDate) {}
