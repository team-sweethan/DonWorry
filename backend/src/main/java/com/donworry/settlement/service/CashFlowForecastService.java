package com.donworry.settlement.service;

import com.donworry.settlement.dto.*;
import com.donworry.settlement.dto.CashFlowForecastResponse.*;
import com.donworry.settlement.repository.CashFlowForecastRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class CashFlowForecastService {
    private static final int LIMIT = 10000;
    private final CashFlowManagementService management;
    private final CashFlowForecastRepository repository;
    private final RecurringExpenseService recurring;
    private final CashFlowBatchSchedule schedule;
    private final CashFlowCalculator calculator;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CashFlowForecastResponse forecast(Long merchant, LocalDate from, LocalDate to) {
        var cutoff = Instant.now();
        var setting = setting(merchant);
        return forecastAt(merchant, from, to, setting, cutoff);
    }

    private CashFlowSettingResponse setting(Long merchant) {
        CashFlowManagementService.validateMerchant(merchant);
        return management
                .settings(merchant)
                .orElseThrow(() ->
                        new SettlementRequestException(409, "SETTLEMENT_SETTING_REQUIRED", "최초 시작 잔액을 먼저 설정해 주세요."));
    }

    CashFlowForecastResponse forecastAt(
            Long merchant, LocalDate from, LocalDate to, CashFlowSettingResponse setting, Instant now) {
        CashFlowManagementService.validateMerchant(merchant);
        CashFlowManagementService.validatePeriod(from, to);
        var today = now.atZone(SettlementReportService.ZONE).toLocalDate();
        if (from.isBefore(setting.baseDate()) || setting.baseDate().isAfter(today) || to.isAfter(today.plusDays(365)))
            throw new IllegalArgumentException("잔액 기준일 이후, 오늘부터 366일 이내의 기간을 조회해 주세요.");
        var start = from.isBefore(today) ? from : today;
        var baseAt = at(setting.baseDate());
        var startAt = at(start);
        var endAt = at(to.plusDays(1));
        var actualUntil = endAt.isBefore(now) ? endAt : now;
        long completed = CashFlowCalculator.money(repository.sumCompleted(merchant, baseAt, now));
        long paid = CashFlowCalculator.add(
                CashFlowCalculator.money(repository.sumPaidExpenses(merchant, baseAt, now)),
                CashFlowCalculator.money(repository.sumPaidRecurring(merchant, baseAt, now)));
        long current = CashFlowCalculator.netBalance(setting.openingBalance(), completed, paid);
        // 기준일부터 조회 시작 전까지는 합계 쿼리로 누적한다. 계정 사용 기간을 366일로 제한하지 않는다.
        var initial = BigDecimal.valueOf(setting.openingBalance())
                .add(repository.sumCompleted(merchant, baseAt, startAt))
                .subtract(repository.sumPaidExpenses(merchant, baseAt, startAt))
                .subtract(repository.sumPaidRecurring(merchant, baseAt, startAt));
        var page = PageRequest.of(0, LIMIT + 1);
        var rows = new ArrayList<Movement>();
        for (var s : bounded(repository.findCompleted(merchant, startAt, actualUntil, page))) {
            rows.add(Movement.builder()
                    .source("SETTLEMENT")
                    .referenceId(s.getSettlementId())
                    .date(date(s.getCompletedAt()))
                    .title("완료 정산")
                    .amount(s.getSettlementAmount())
                    .classification("COMPLETED")
                    .eligibleAt(s.getScheduledAt())
                    .reflectedAt(s.getCompletedAt())
                    .build());
        }
        long overdue = 0;
        for (var s : bounded(repository.findScheduled(merchant, endAt.isAfter(now) ? endAt : now, now, page))) {
            var eligible = s.getScheduledAt() == null ? s.getExpectedSettlementAt() : s.getScheduledAt();
            var batch = schedule.projectedAt(eligible);
            if (batch.isBefore(now)) {
                overdue = CashFlowCalculator.add(overdue, s.getSettlementAmount());
                continue;
            }
            if (date(batch).isAfter(to)) continue;
            rows.add(Movement.builder()
                    .source("SETTLEMENT")
                    .referenceId(s.getSettlementId())
                    .date(date(batch))
                    .title("예정 정산")
                    .amount(s.getSettlementAmount())
                    .classification(s.getScheduledAt() == null ? "EXPECTED" : "SCHEDULED")
                    .eligibleAt(eligible)
                    .reflectedAt(batch)
                    .build());
        }
        for (var e : bounded(repository.findExpenses(merchant, startAt, actualUntil, today, to, page))) {
            boolean paidBeforeCutoff = e.getPaidAt() != null && e.getPaidAt().isBefore(now);
            rows.add(Movement.builder()
                    .source("EXPENSE")
                    .referenceId(e.getExpenseId())
                    .occurrenceDate(e.getExpenseDate())
                    .date(paidBeforeCutoff ? date(e.getPaidAt()) : e.getExpenseDate())
                    .title(e.getTitle())
                    .amount(e.getAmount())
                    .classification(paidBeforeCutoff ? "PAID" : "PLANNED")
                    .reflectedAt(paidBeforeCutoff ? e.getPaidAt() : null)
                    .build());
        }
        for (var p : bounded(repository.findPaidRecurring(merchant, startAt, actualUntil, page))) {
            rows.add(Movement.builder()
                    .source("RECURRING_EXPENSE")
                    .referenceId(p.getRecurringExpenseId())
                    .occurrenceDate(p.getOccurrenceDate())
                    .date(date(p.getPaidAt()))
                    .title(p.getTitle())
                    .amount(p.getAmount())
                    .classification("PAID")
                    .reflectedAt(p.getPaidAt())
                    .build());
        }
        if (!to.isBefore(today)) {
            // 원래 예정일이 미래여도 이미 완료한 회차는 이 목록의 예정액에 재포함하지 않는다.
            for (var p : recurring.pendingOccurrencesAsOf(merchant, today, to, now)) {
                if (p.paidAt() != null) continue;
                rows.add(Movement.builder()
                        .source("RECURRING_EXPENSE")
                        .referenceId(p.recurringExpenseId())
                        .occurrenceDate(p.expenseDate())
                        .date(p.expenseDate())
                        .title(p.title())
                        .amount(p.amount())
                        .classification("PLANNED")
                        .build());
            }
        }
        bounded(rows);
        var result = calculator.calculate(initial, start, from, to, rows);
        return CashFlowForecastResponse.builder()
                .baseDate(setting.baseDate())
                .openingBalance(setting.openingBalance())
                .asOf(now)
                .from(from)
                .to(to)
                .currentBalance(current)
                .completedIncomeSinceBase(completed)
                .paidExpensesSinceBase(paid)
                .periodOpeningBalance(result.periodOpeningBalance())
                .totalIncome(result.totalIncome())
                .totalExpenses(result.totalExpenses())
                .closingBalance(result.closingBalance())
                .firstShortageDate(result.firstShortageDate())
                .lowestBalance(result.lowestBalance())
                .maximumShortage(result.maximumShortage())
                .undatedIncome(CashFlowCalculator.money(repository.sumUndated(merchant)))
                .overdueIncome(overdue)
                .canceledIncome(CashFlowCalculator.money(repository.sumCanceled(merchant)))
                .batchCron(schedule.expression())
                .batchEnabled(schedule.enabled())
                .carryForwardMovements(result.carryForwardMovements())
                .days(result.days())
                .shortages(result.shortages())
                .build();
    }

    private static Instant at(LocalDate date) {
        return date.atStartOfDay(SettlementReportService.ZONE).toInstant();
    }

    private static LocalDate date(Instant at) {
        return at.atZone(SettlementReportService.ZONE).toLocalDate();
    }

    private <T> List<T> bounded(List<T> rows) {
        if (rows.size() > LIMIT)
            throw new SettlementRequestException(
                    422, "SETTLEMENT_CASH_FLOW_TOO_LARGE", "계산 대상이 10,000건을 초과합니다. 조회 기간과 미완료 정산을 확인해 주세요.");
        return rows;
    }
}
