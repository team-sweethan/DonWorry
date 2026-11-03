package com.donworry.settlement.dto;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record RecurringExpenseOccurrence(
        Long recurringExpenseId,
        String title,
        LocalDate expenseDate,
        Long amount,
        String category,
        String memo,
        Long occurrencePaymentId,
        Instant paidAt,
        LocalDate paidDate) {}
