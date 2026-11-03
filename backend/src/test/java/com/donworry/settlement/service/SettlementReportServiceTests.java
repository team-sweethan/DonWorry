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
        when(repository.sumUndated(1L)).thenReturn(java.math.BigDecimal.valueOf(100));
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

    private SettlementReportProjection row(long amount, String classification, LocalDate date) {
        var p = mock(SettlementReportProjection.class);
        when(p.getOrderId()).thenReturn("P6-SAFE");
        when(p.getStatus())
                .thenReturn(
                        classification.equals("COMPLETED")
                                ? com.donworry.settlement.entity.SettlementStatus.COMPLETED
                                : com.donworry.settlement.entity.SettlementStatus.SCHEDULED);
        var at = date.atStartOfDay(SettlementReportService.ZONE).toInstant();
        if (classification.equals("COMPLETED")) when(p.getCompletedAt()).thenReturn(at);
        else if (classification.equals("SCHEDULED")) when(p.getScheduledAt()).thenReturn(at);
        else when(p.getExpectedSettlementAt()).thenReturn(at);
        when(p.getSettlementAmount()).thenReturn(amount);
        return p;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "COMPLETED,1",
        "SCHEDULED,1",
        "EXPECTED,1",
        "COMPLETED,-1",
        "SCHEDULED,-1",
        "EXPECTED,-1"
    })
    void report_whenPeriodSumExceedsBrowserPrecision_returns422(String classification, int sign) {
        var repository = mock(SettlementRepository.class);
        var day = LocalDate.of(2026, 11, 1);
        var rows =
                List.of(row(sign * 9007199254740991L, classification, day), row(sign, classification, day.plusDays(1)));
        when(repository.findReport(anyLong(), any(), any(), any())).thenReturn(rows);
        assertThatThrownBy(() -> new SettlementReportService(repository, mock(OrderFulfillmentItemRepository.class))
                        .report(1L, day, day.plusDays(1)))
                .isInstanceOfSatisfying(SettlementRequestException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("SETTLEMENT_REPORT_AMOUNT_OVERFLOW");
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {9007199254740991L, -9007199254740991L})
    void report_whenExactlySafeLimit_returnsExactAmount(long amount) {
        var repository = mock(SettlementRepository.class);
        var day = LocalDate.of(2026, 11, 1);
        var rows = List.of(row(amount, "COMPLETED", day));
        when(repository.findReport(anyLong(), any(), any(), any())).thenReturn(rows);
        when(repository.sumUndated(1L)).thenReturn(java.math.BigDecimal.ZERO);
        var result = new SettlementReportService(repository, mock(OrderFulfillmentItemRepository.class))
                .report(1L, day, day);
        assertThat(result.completedAmount()).isEqualTo(amount);
        assertThat(result.days().getFirst().completedAmount()).isEqualTo(amount);
    }

    @Test
    void report_whenUndatedSqlSumExceedsLong_returns422() {
        var repository = mock(SettlementRepository.class);
        when(repository.findReport(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(repository.sumUndated(1L)).thenReturn(new java.math.BigDecimal("18446744073709551614"));
        var day = LocalDate.of(2026, 11, 1);
        assertThatThrownBy(() -> new SettlementReportService(repository, mock(OrderFulfillmentItemRepository.class))
                        .report(1L, day, day))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void report_whenDailyTotalExceedsSafeLimit_returns422() {
        var repository = mock(SettlementRepository.class);
        var day = LocalDate.of(2026, 11, 1);
        var rows = List.of(row(9007199254740991L, "SCHEDULED", day), row(1L, "SCHEDULED", day));
        when(repository.findReport(anyLong(), any(), any(), any())).thenReturn(rows);
        assertThatThrownBy(() -> new SettlementReportService(repository, mock(OrderFulfillmentItemRepository.class))
                        .report(1L, day, day))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }
}
