package com.donworry.settlement.dto;

import com.donworry.settlement.entity.PlannedExpense;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record ExpenseResponse(
        Long expenseId,
        String title,
        LocalDate expenseDate,
        Long amount,
        String category,
        String memo,
        Instant paidAt) {
    public static ExpenseResponse from(PlannedExpense e) {
        return ExpenseResponse.builder()
                .expenseId(e.getExpenseId())
                .title(e.getTitle())
                .expenseDate(e.getExpenseDate())
                .amount(e.getAmount())
                .category(e.getCategory().name())
                .memo(e.getMemo())
                .paidAt(e.getPaidAt())
                .build();
    }
}
