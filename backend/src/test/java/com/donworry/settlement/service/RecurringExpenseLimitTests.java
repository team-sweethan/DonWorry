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
        givenRules(28);
    }

    private void givenRules(int count) {
        var entities = IntStream.rangeClosed(1, count)
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

    private void givenCompletedDates(boolean leaveLastUnpaid) {
        when(payments.findCompletedOccurrences(eq(2L), anyCollection(), eq(from), eq(to)))
                .thenAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(1);
                    return ids.stream()
                            .flatMap(id -> dates.stream()
                                    .filter(date -> !(leaveLastUnpaid && id == 28L && date.equals(to)))
                                    .map(date -> new RecurringExpensePaymentRepository.CompletedOccurrence() {
                                        public Long getRecurringExpenseId() {
                                            return id;
                                        }

                                        public LocalDate getOccurrenceDate() {
                                            return date;
                                        }
                                    }))
                            .toList();
                });
    }

    // 완료된 규칙이 많아 응답이 비어도 완료 키 조회는 100개 ID 묶음당 한 번만 실행한다.
    @Test
    void occurrences_whenManyRulesAlreadyPaid_boundsCompletionQueries() {
        givenRules(201);
        givenCompletedDates(false);
        assertThat(service.occurrences(2L, from, to)).isEmpty();
        verify(payments, times(3))
                .findCompletedOccurrences(eq(2L), argThat(ids -> ids.size() <= 100), eq(from), eq(to));
    }

    // 적용 규칙이 없으면 빈 IN 조건으로 완료 키 조회를 실행하지 않는다.
    @Test
    void occurrences_whenNoRules_skipsCompletionLookup() {
        assertThat(service.occurrences(2L, from, to)).isEmpty();
        verify(payments, never()).findCompletedOccurrences(anyLong(), anyCollection(), any(), any());
    }

    // 전체 예정 회차 10,248건이 다른 날 이미 완료되었다면 빈 응답이 정상이다.
    @Test
    void occurrences_whenOverLimitButAllPaidOutsidePeriod_returnsEmpty() {
        givenRules();
        givenCompletedDates(false);
        assertThat(service.occurrences(2L, from, to)).isEmpty();
        verify(payments, times(1)).findCompletedOccurrences(eq(2L), anyCollection(), eq(from), eq(to));
    }

    // 완료 제외 후 남는 회차만 한도에 포함하고, 실제 미완료 10,000건 초과는 계속 거절한다.
    @Test
    void occurrences_whenOverLimitButOnlyOneUnpaid_returnsOne() {
        givenRules();
        givenCompletedDates(true);
        assertThat(service.occurrences(2L, from, to)).singleElement().satisfies(p -> {
            assertThat(p.recurringExpenseId()).isEqualTo(28L);
            assertThat(p.expenseDate()).isEqualTo(to);
            assertThat(p.paidAt()).isNull();
        });
    }

    @Test
    void occurrences_whenUnpaidRowsExceedLimit_stillRejects() {
        givenRules();

        assertThatThrownBy(() -> service.occurrences(2L, from, to))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }
}
