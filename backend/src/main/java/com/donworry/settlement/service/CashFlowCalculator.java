package com.donworry.settlement.service;

import com.donworry.settlement.dto.CashFlowForecastResponse.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.Builder;
import org.springframework.stereotype.Component;

@Component
public class CashFlowCalculator {
    private static final long MAX_SAFE = 9007199254740991L;

    @Builder
    public record Result(
            long periodOpeningBalance,
            long totalIncome,
            long totalExpenses,
            long closingBalance,
            LocalDate firstShortageDate,
            long lowestBalance,
            long maximumShortage,
            List<Movement> carryForwardMovements,
            List<Day> days,
            List<Shortage> shortages) {}

    public Result calculate(long opening, LocalDate start, LocalDate from, LocalDate to, List<Movement> movements) {
        CashFlowManagementService.validatePeriod(from, to);
        if (start.isAfter(from)) throw new IllegalArgumentException("계산 시작일이 조회 시작일보다 늦습니다.");
        money(BigDecimal.valueOf(opening));
        var grouped = new TreeMap<LocalDate, List<Movement>>();
        var unique = new HashSet<String>();
        for (var m : movements) {
            if (!unique.add(m.source() + ":" + m.referenceId() + ":" + m.occurrenceDate()))
                throw new IllegalStateException("같은 현금흐름 항목이 중복 조회됐습니다.");
            if (m.amount() < 0) throw new IllegalStateException("금액은 음수일 수 없습니다.");
            money(BigDecimal.valueOf(m.amount()));
            if (!m.date().isBefore(start) && !m.date().isAfter(to))
                grouped.computeIfAbsent(m.date(), ignored -> new ArrayList<>()).add(m);
        }
        long balance = opening, periodOpening = opening, totalIncome = 0, totalExpense = 0;
        var carry = new ArrayList<Movement>();
        var days = new ArrayList<Day>();
        for (var date = start; !date.isAfter(to); date = date.plusDays(1)) {
            var rows = grouped.getOrDefault(date, List.of());
            long completed = sum(rows, "COMPLETED"),
                    scheduled = sum(rows, "SCHEDULED"),
                    expected = sum(rows, "EXPECTED"),
                    paid = sum(rows, "PAID"),
                    planned = sum(rows, "PLANNED");
            long income = add(add(completed, scheduled), expected), expense = add(paid, planned), before = balance;
            balance = netBalance(balance, income, expense);
            if (date.isBefore(from)) {
                periodOpening = balance;
                carry.addAll(rows);
                continue;
            }
            totalIncome = add(totalIncome, income);
            totalExpense = add(totalExpense, expense);
            days.add(Day.builder()
                    .date(date)
                    .openingBalance(before)
                    .completedIncome(completed)
                    .scheduledIncome(scheduled)
                    .expectedIncome(expected)
                    .paidExpenses(paid)
                    .plannedExpenses(planned)
                    .closingBalance(balance)
                    .movements(rows)
                    .build());
        }
        var shortages = new ArrayList<Shortage>();
        long lowest = days.stream().mapToLong(Day::closingBalance).min().orElse(periodOpening);
        for (var day : days) {
            if (day.closingBalance() >= 0) continue;
            long amount = subtract(0, day.closingBalance());
            var last = shortages.isEmpty() ? null : shortages.getLast();
            if (last != null && last.to().plusDays(1).equals(day.date())) {
                shortages.set(
                        shortages.size() - 1,
                        new Shortage(last.from(), day.date(), last.days() + 1, Math.max(last.maximumAmount(), amount)));
            } else shortages.add(new Shortage(day.date(), day.date(), 1, amount));
        }
        return Result.builder()
                .periodOpeningBalance(periodOpening)
                .totalIncome(totalIncome)
                .totalExpenses(totalExpense)
                .closingBalance(balance)
                .firstShortageDate(
                        shortages.isEmpty() ? null : shortages.getFirst().from())
                .lowestBalance(lowest)
                .maximumShortage(shortages.stream()
                        .mapToLong(Shortage::maximumAmount)
                        .max()
                        .orElse(0))
                .carryForwardMovements(List.copyOf(carry))
                .days(List.copyOf(days))
                .shortages(List.copyOf(shortages))
                .build();
    }

    private long sum(List<Movement> rows, String classification) {
        return rows.stream()
                .filter(m -> m.classification().equals(classification))
                .mapToLong(Movement::amount)
                .reduce(0, CashFlowCalculator::add);
    }

    public static long money(BigDecimal value) {
        try {
            long result = value.longValueExact();
            if (result < -MAX_SAFE || result > MAX_SAFE) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException e) {
            throw new SettlementRequestException(422, "SETTLEMENT_CASH_FLOW_AMOUNT_OVERFLOW", "금액 합계가 지원 범위를 초과합니다.");
        }
    }

    public static long netBalance(long opening, long income, long expenses) {
        return money(
                BigDecimal.valueOf(opening).add(BigDecimal.valueOf(income)).subtract(BigDecimal.valueOf(expenses)));
    }

    public static long add(long a, long b) {
        return money(BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)));
    }

    public static long subtract(long a, long b) {
        return money(BigDecimal.valueOf(a).subtract(BigDecimal.valueOf(b)));
    }
}
