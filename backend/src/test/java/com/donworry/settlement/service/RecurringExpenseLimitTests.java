package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecurringExpenseLimitTests {
    private final RecurringExpenseRepository rules = mock(RecurringExpenseRepository.class);
    private final RecurringExpensePaymentRepository payments = mock(RecurringExpensePaymentRepository.class);
    private final RecurringExpenseService service = new RecurringExpenseService(
            rules, new RecurringExpenseExpander(), payments, mock(CashFlowSettingRepository.class));
    private final LocalDate from = LocalDate.of(2028, 1, 1);
    private final LocalDate to = from.plusDays(365);
    private final List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();

    private void givenRules() {
        var entities = IntStream.rangeClosed(1, 28)
                .mapToObj(i -> {
                    var rule = RecurringExpense.recurring(
                            2L,
                            "재료",
                            100L,
                            ExpenseCategory.MATERIAL,
                            null,
                            ExpenseFrequency.DAILY,
                            from,
                            null,
                            Instant.parse("2027-12-01T00:00:00Z"));
                    ReflectionTestUtils.setField(rule, "recurringExpenseId", (long) i);
                    return rule;
                })
                .toList();
        when(rules.findApplicable(2L, from, to)).thenReturn(entities);
        when(payments.findByMerchantIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(eq(2L), any(), any(), any()))
                .thenReturn(List.of());
    }

    // 전체 예정 회차 10,248건이 다른 날 이미 완료되었다면 빈 응답이 정상이다.
    @Test
    void occurrences_whenOverLimitButAllPaidOutsidePeriod_returnsEmpty() {
        givenRules();
        when(payments.findCompletedDates(eq(2L), anyLong(), eq(from), eq(to))).thenReturn(dates);
        assertThat(service.occurrences(2L, from, to)).isEmpty();
    }

    // 완료 제외 후 남는 회차만 한도에 포함하고, 실제 미완료 10,000건 초과는 계속 거절한다.
    @Test
    void occurrences_whenOverLimitButOnlyOneUnpaid_returnsOne() {
        givenRules();
        when(payments.findCompletedDates(eq(2L), anyLong(), eq(from), eq(to))).thenReturn(dates);
        when(payments.findCompletedDates(2L, 28L, from, to)).thenReturn(dates.subList(0, 365));
        assertThat(service.occurrences(2L, from, to)).singleElement().satisfies(p -> {
            assertThat(p.recurringExpenseId()).isEqualTo(28L);
            assertThat(p.expenseDate()).isEqualTo(to);
            assertThat(p.paidAt()).isNull();
        });
    }

    @Test
    void occurrences_whenUnpaidRowsExceedLimit_stillRejects() {
        givenRules();
        when(payments.findCompletedDates(eq(2L), anyLong(), eq(from), eq(to))).thenReturn(List.of());
        assertThatThrownBy(() -> service.occurrences(2L, from, to))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }
}
