package com.donworry.settlement.service;

import com.donworry.settlement.dto.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RecurringExpenseExpander {
    public List<RecurringExpenseOccurrence> expand(List<RecurringExpenseResponse> rules, LocalDate from, LocalDate to) {
        return expand(rules, from, to, Map.of());
    }

    public List<RecurringExpenseOccurrence> expand(
            List<RecurringExpenseResponse> rules,
            LocalDate from,
            LocalDate to,
            Map<Long, Set<LocalDate>> completedDates) {
        CashFlowManagementService.validatePeriod(from, to);
        var result = new ArrayList<RecurringExpenseOccurrence>();
        var seen = new HashSet<Long>();
        for (var r : rules) {
            if (!seen.add(r.recurringExpenseId())) throw new IllegalStateException("반복 규칙이 중복 조회됐습니다.");
            if (!r.frequency().equals("DAILY")
                    && !r.frequency().equals("WEEKLY")
                    && !r.frequency().equals("MONTHLY")) throw new IllegalStateException("반복 주기가 올바르지 않습니다.");
            for (var d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (d.isBefore(r.startDate())
                        || (r.endDate() != null && d.isAfter(r.endDate()))
                        || (r.stoppedFrom() != null && !d.isBefore(r.stoppedFrom()))) continue;
                boolean occurs = r.frequency().equals("DAILY")
                        || (r.frequency().equals("WEEKLY")
                                ? d.getDayOfWeek() == r.startDate().getDayOfWeek()
                                : d.getDayOfMonth() == Math.min(r.startDate().getDayOfMonth(), d.lengthOfMonth()));
                if (occurs
                        && !completedDates
                                .getOrDefault(r.recurringExpenseId(), Set.of())
                                .contains(d)) {
                    if (result.size() >= 10000)
                        throw new SettlementRequestException(
                                422, "SETTLEMENT_RECURRING_PERIOD_TOO_LARGE", "조회 기간을 줄여 주세요.");
                    result.add(RecurringExpenseOccurrence.builder()
                            .recurringExpenseId(r.recurringExpenseId())
                            .title(r.title())
                            .expenseDate(d)
                            .amount(r.amount())
                            .category(r.category())
                            .memo(r.memo())
                            .build());
                }
            }
        }
        result.sort(Comparator.comparing(RecurringExpenseOccurrence::expenseDate)
                .thenComparing(RecurringExpenseOccurrence::recurringExpenseId));
        return List.copyOf(result);
    }
}
