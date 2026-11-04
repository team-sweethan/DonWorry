package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.repository.SettlementCalendarRepository;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaptureSettlementScheduleTests {
    private final SettlementCalendarRepository calendar = mock(SettlementCalendarRepository.class);
    private final CaptureSettlementSchedule schedule = new CaptureSettlementSchedule(calendar);

    private void calendar() {
        var holidays = Set.of(
                LocalDate.parse("2026-12-25"),
                LocalDate.parse("2027-01-01"),
                LocalDate.parse("2026-09-24"),
                LocalDate.parse("2026-09-25"),
                LocalDate.parse("2026-10-05"));
        when(calendar.findBusinessDay(any())).thenAnswer(call -> {
            LocalDate d = call.getArgument(0);
            if (d.getYear() < 2026 || d.getYear() > 2027) return Optional.empty();
            return Optional.of(d.getDayOfWeek() != DayOfWeek.SATURDAY
                    && d.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(d));
        });
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-02T00:00:00Z,2026-11-04T15:00:00Z",
        "2026-11-06T14:59:59Z,2026-11-10T15:00:00Z",
        "2026-11-07T01:00:00Z,2026-11-10T15:00:00Z",
        "2026-09-23T00:00:00Z,2026-09-29T15:00:00Z",
        "2026-10-02T00:00:00Z,2026-10-07T15:00:00Z",
        "2026-12-24T00:00:00Z,2026-12-29T15:00:00Z",
        "2026-12-30T00:00:00Z,2027-01-04T15:00:00Z"
    })
    void settlementAt_whenWeekendHolidayOrYearBoundary_usesThirdFollowingBusinessDate(String capture, String due) {
        calendar();
        assertThat(schedule.settlementAt(Instant.parse(capture))).isEqualTo(Instant.parse(due));
    }

    @Test
    void settlementAt_whenSameKoreanDay_ignoresCaptureTime() {
        calendar();
        assertThat(schedule.settlementAt(Instant.parse("2026-11-01T15:00:00Z")))
                .isEqualTo(schedule.settlementAt(Instant.parse("2026-11-02T14:59:59.999999Z")));
        assertThat(schedule.settlementAt(Instant.parse("2026-11-02T15:00:00Z")))
                .isNotEqualTo(schedule.settlementAt(Instant.parse("2026-11-02T14:59:59Z")));
    }

    @Test
    void settlementAt_whenCalendarMissing_rejectsWithoutGuessing() {
        calendar();
        assertThatIllegalStateException()
                .isThrownBy(() -> schedule.settlementAt(Instant.parse("2025-12-31T00:00:00Z")));
        assertThatIllegalStateException()
                .isThrownBy(() -> schedule.settlementAt(Instant.parse("2027-12-31T00:00:00Z")));
        when(calendar.findBusinessDay(LocalDate.parse("2026-11-03"))).thenReturn(Optional.empty());
        assertThatIllegalStateException()
                .isThrownBy(() -> schedule.settlementAt(Instant.parse("2026-11-02T00:00:00Z")));
        assertThatIllegalArgumentException().isThrownBy(() -> schedule.settlementAt(null));
    }
}
