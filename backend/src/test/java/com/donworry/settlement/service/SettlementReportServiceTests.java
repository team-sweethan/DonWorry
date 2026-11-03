package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.repository.*;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementReportServiceTests {
    // 잘못된 기간은 DB 접근 전에 거절한다.
    @Test
    void report_whenPeriodInvalid_rejectsBeforeQuery() {
        var repository = mock(SettlementRepository.class);
        var items = mock(OrderFulfillmentItemRepository.class);
        var service = new SettlementReportService(repository, items);
        var from = LocalDate.of(2026, 1, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> service.report(1L, from, from.minusDays(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.report(1L, from, from.plusDays(366)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.report(null, from, from));
        verifyNoInteractions(repository, items);
    }

    // 빈 기간도 날짜별 0원 값을 반환하며 빈 IN 조건 조회를 만들지 않는다.
    @Test
    void report_whenEmpty_returnsZeroDaysWithoutItemQuery() {
        var repository = mock(SettlementRepository.class);
        var items = mock(OrderFulfillmentItemRepository.class);
        when(repository.findReport(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(repository.sumUndated(1L)).thenReturn(100L);
        var from = LocalDate.of(2026, 11, 1);
        var result = new SettlementReportService(repository, items).report(1L, from, from.plusDays(1));
        assertThat(result.days()).hasSize(2);
        assertThat(result.completedAmount()).isZero();
        assertThat(result.scheduledAmount()).isZero();
        assertThat(result.entries()).isEmpty();
        assertThat(result.undatedAmount()).isEqualTo(100);
        verifyNoInteractions(items);
    }

    // 과다 조회 결과는 일부만 합산하지 않고 기간 축소 오류로 반환한다.
    @Test
    void report_whenTooManyEntries_rejectsPartialTotals() {
        var repository = mock(SettlementRepository.class);
        var items = mock(OrderFulfillmentItemRepository.class);
        when(repository.findReport(anyLong(), any(), any(), any()))
                .thenReturn(Collections.nCopies(10001, mock(SettlementReportProjection.class)));
        var day = LocalDate.of(2026, 11, 1);
        assertThatThrownBy(() -> new SettlementReportService(repository, items).report(1L, day, day))
                .isInstanceOf(SettlementRequestException.class);
        verifyNoInteractions(items);
    }
}
