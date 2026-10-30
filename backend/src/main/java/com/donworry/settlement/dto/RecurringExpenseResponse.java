package com.donworry.settlement.dto;

import com.donworry.settlement.entity.RecurringExpense;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record RecurringExpenseResponse(
        Long recurringExpenseId,
        String title,
        Long amount,
        String category,
        String memo,
        String frequency,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate stoppedFrom) {
    public static RecurringExpenseResponse from(RecurringExpense e) {
        return RecurringExpenseResponse.builder()
                .recurringExpenseId(e.getRecurringExpenseId())
                .title(e.getTitle())
                .amount(e.getAmount())
                .category(e.getCategory().name())
                .memo(e.getMemo())
                .frequency(e.getFrequency().name())
                .startDate(e.getStartDate())
                .endDate(e.getEndDate())
                .stoppedFrom(e.getStoppedFrom())
                .build();
    }
}
