package com.donworry.settlement.dto;

import java.time.*;
import java.util.List;
import lombok.Builder;

@Builder
public record CashFlowForecastResponse(
        LocalDate baseDate,
        long openingBalance,
        Instant asOf,
        LocalDate from,
        LocalDate to,
        long currentBalance,
        long completedIncomeSinceBase,
        long paidExpensesSinceBase,
        long periodOpeningBalance,
        long totalIncome,
        long totalExpenses,
        long closingBalance,
        LocalDate firstShortageDate,
        long lowestBalance,
        long maximumShortage,
        long undatedIncome,
        long overdueIncome,
        long canceledIncome,
        String batchCron,
        boolean batchEnabled,
        List<Movement> carryForwardMovements,
        List<Day> days,
        List<Shortage> shortages) {
    public CashFlowForecastResponse {
        carryForwardMovements = List.copyOf(carryForwardMovements);
        days = List.copyOf(days);
        shortages = List.copyOf(shortages);
    }

    @Builder
    public record Movement(
            String source,
            Long referenceId,
            LocalDate occurrenceDate,
            LocalDate date,
            String title,
            long amount,
            String classification,
            Instant eligibleAt,
            Instant reflectedAt) {}

    @Builder
    public record Day(
            LocalDate date,
            long openingBalance,
            long completedIncome,
            long scheduledIncome,
            long expectedIncome,
            long paidExpenses,
            long plannedExpenses,
            long closingBalance,
            List<Movement> movements) {
        public Day {
            movements = List.copyOf(movements);
        }
    }

    @Builder
    public record Shortage(LocalDate from, LocalDate to, int days, long maximumAmount) {}
}
