package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.settlement.dto.RecurringExpenseResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurringExpenseTests {
    private final RecurringExpenseExpander expander = new RecurringExpenseExpander();

    // 월말 규칙은 짧은 달에만 말일로 보정하고 다음 달에는 원래 날짜로 돌아온다.
    @Test
    void expand_whenMonthEndsDiffer_preservesOriginalDay() {
        var dates = expander.expand(
                List.of(rule("MONTHLY", LocalDate.of(2028, 1, 31), null, null)),
                LocalDate.of(2028, 1, 1),
                LocalDate.of(2028, 4, 30));
        assertThat(dates)
                .extracting(p -> p.expenseDate())
                .containsExactly(
                        LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29),
                        LocalDate.of(2028, 3, 31), LocalDate.of(2028, 4, 30));
    }

    // 종료일은 포함하되 중단일부터는 새 예정 회차를 만들지 않는다.
    @Test
    void expand_whenStopped_obeysInclusiveEndAndExclusiveStop() {
        var start = LocalDate.of(2026, 10, 1);
        assertThat(expander.expand(List.of(rule("DAILY", start, start.plusDays(2), null)), start, start.plusDays(4)))
                .hasSize(3);
        assertThat(expander.expand(List.of(rule("DAILY", start, null, start.plusDays(2))), start, start.plusDays(4)))
                .hasSize(2);
        assertThat(expander.expand(List.of(rule("WEEKLY", start, null, null)), start, start.plusDays(14)))
                .extracting(p -> p.expenseDate())
                .containsExactly(start, start.plusDays(7), start.plusDays(14));
    }

    @Test
    void expand_whenRangeExceeds366Days_rejects() {
        var start = LocalDate.of(2026, 1, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> expander.expand(List.of(), start, start.plusDays(366)));
    }

    private RecurringExpenseResponse rule(String frequency, LocalDate start, LocalDate end, LocalDate stopped) {
        return RecurringExpenseResponse.builder()
                .recurringExpenseId(1L)
                .title("임대료")
                .amount(100L)
                .category("RENT")
                .frequency(frequency)
                .startDate(start)
                .endDate(end)
                .stoppedFrom(stopped)
                .build();
    }
}
