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
        long completed = CashFlowCalculator.money(repository.sumPaidOut(merchant, baseAt, now));
        long paid = CashFlowCalculator.add(
                CashFlowCalculator.money(repository.sumPaidExpenses(merchant, baseAt, now)),
                CashFlowCalculator.money(repository.sumPaidRecurring(merchant, baseAt, now)));
        long current = CashFlowCalculator.netBalance(setting.openingBalance(), completed, paid);
        // 기준일부터 조회 시작 전까지는 합계 쿼리로 누적한다. 계정 사용 기간을 366일로 제한하지 않는다.
        var initial = BigDecimal.valueOf(setting.openingBalance())
                .add(repository.sumPaidOut(merchant, baseAt, startAt))
                .subtract(repository.sumPaidExpenses(merchant, baseAt, startAt))
                .subtract(repository.sumPaidRecurring(merchant, baseAt, startAt));
        var page = PageRequest.of(0, LIMIT + 1);
        var rows = new ArrayList<Movement>();
        for (var s : bounded(repository.findPaidOut(merchant, startAt, actualUntil, page))) {
            rows.add(Movement.builder()
                    .source("PAYOUT")
                    .referenceId(s.getPayoutId())
                    .date(date(s.getCompletedAt()))
                    .title("실제 정산 지급")
                    .amount(s.getPayoutAmount())
                    .settlementAmount(CashFlowCalculator.money(BigDecimal.valueOf(s.getSettlementTotal())))
                    .refundDeductionAmount(CashFlowCalculator.money(BigDecimal.valueOf(s.getRefundDeductionTotal())))
                    .classification("COMPLETED")
                    .reflectedAt(s.getCompletedAt())
                    .build());
        }
        var outstanding = repository.outstandingRecovery(merchant, now);
        if (outstanding.signum() < 0) throw new IllegalStateException("환불 회수 잔여액이 음수입니다.");
        var debt = outstanding;
        long overdue = 0;
        var projected = new ArrayList<Movement>();
        var sealedDates = bounded(repository.findPaidOut(merchant, at(today), now, page)).stream()
                .filter(p -> p.getCompletedAt().isBefore(now))
                .map(p -> p.getPayoutDate())
                .collect(java.util.stream.Collectors.toSet());
        for (var s : bounded(repository.findScheduled(merchant, endAt.isAfter(now) ? endAt : now, now, page))) {
            var eligible = s.getScheduledAt() == null ? s.getExpectedSettlementAt() : s.getScheduledAt();
            var batch = schedule.projectedAt(eligible);
            if (sealedDates.contains(date(batch)))
                batch = schedule.projectedAt(at(date(batch).plusDays(1)));
            if (batch.isBefore(now)) {
                overdue = CashFlowCalculator.add(overdue, s.getSettlementAmount());
                continue;
            }
            if (date(batch).isAfter(to)) continue;
            projected.add(Movement.builder()
                    .source("SETTLEMENT")
                    .referenceId(s.getSettlementId())
                    .date(date(batch))
                    .title("예정 정산 지급")
                    .amount(CashFlowCalculator.money(BigDecimal.valueOf(s.getSettlementAmount())))
                    .settlementAmount(s.getSettlementAmount())
                    .classification(s.getScheduledAt() == null ? "EXPECTED" : "SCHEDULED")
                    .eligibleAt(eligible)
                    .reflectedAt(batch)
                    .build());
        }
        // 조회 시작 전의 예정 지급도 먼저 차감해야 미래 기간만 조회해도 회수액을 두 번 빼지 않는다.
        projected.sort(Comparator.comparing(Movement::date).thenComparing(Movement::referenceId));
        for (var m : projected) {
            var deduction = debt.min(BigDecimal.valueOf(m.amount()));
            long deducted = CashFlowCalculator.money(deduction);
            debt = debt.subtract(deduction);
            rows.add(Movement.builder()
                    .source(m.source())
                    .referenceId(m.referenceId())
                    .date(m.date())
                    .title(m.title())
                    .amount(m.amount() - deducted)
                    .classification(m.classification())
                    .eligibleAt(m.eligibleAt())
                    .reflectedAt(m.reflectedAt())
                    .settlementAmount(m.amount())
                    .refundDeductionAmount(deducted)
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
                .outstandingRecoveryAmount(CashFlowCalculator.money(outstanding))
                .projectedRefundDeduction(CashFlowCalculator.money(outstanding.subtract(debt)))
                .remainingRecoveryAmount(CashFlowCalculator.money(debt))
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
