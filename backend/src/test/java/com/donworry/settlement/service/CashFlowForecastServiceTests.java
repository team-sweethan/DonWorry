package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.dto.*;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.CashFlowForecastRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CashFlowForecastServiceTests {
    private final CashFlowManagementService management = mock(CashFlowManagementService.class);
    private final CashFlowForecastRepository repository = mock(CashFlowForecastRepository.class);
    private final RecurringExpenseService recurring = mock(RecurringExpenseService.class);
    private final CashFlowForecastService service = new CashFlowForecastService(
            management,
            repository,
            recurring,
            new CashFlowBatchSchedule("0 0 3 * * *", false),
            new CashFlowCalculator());
    private final LocalDate today = LocalDate.of(2026, 11, 2);
    private final Instant now = Instant.parse("2026-11-02T03:00:00Z");
    private final CashFlowSettingResponse setting = new CashFlowSettingResponse(1000L, LocalDate.of(2024, 1, 1));

    private Instant at(LocalDate d) {
        return d.atStartOfDay(SettlementReportService.ZONE).toInstant();
    }

    @BeforeEach
    void sums() {
        when(repository.sumCompleted(anyLong(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(repository.sumPaidExpenses(anyLong(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(repository.sumPaidRecurring(anyLong(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(repository.sumUndated(anyLong())).thenReturn(BigDecimal.ZERO);
        when(repository.sumCanceled(anyLong())).thenReturn(BigDecimal.ZERO);
    }

    private Settlement settlement(long id, long amount, Instant scheduled, Instant expected, Instant completed) {
        var s = Settlement.scheduled(id, id, 2L, amount, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, amount, now);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "settlementId", id);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "scheduledAt", scheduled);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "expectedSettlementAt", expected);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "completedAt", completed);
        return s;
    }

    // 과거 누적 실제 거래와 오늘 실제·예정 지출을 분리하고 미래 선지출 회차를 재차감하지 않는다.
    @Test
    void forecast_whenActualAndProjectedMixed_keepsCurrentBalanceSeparate() {
        var base = at(setting.baseDate());
        when(repository.sumCompleted(2L, base, now)).thenReturn(BigDecimal.valueOf(1000));
        when(repository.sumPaidExpenses(2L, base, now)).thenReturn(BigDecimal.valueOf(150));
        when(repository.sumPaidRecurring(2L, base, now)).thenReturn(BigDecimal.valueOf(50));
        when(repository.sumCompleted(2L, base, at(today))).thenReturn(BigDecimal.valueOf(500));
        when(repository.sumPaidExpenses(2L, base, at(today))).thenReturn(BigDecimal.valueOf(100));
        when(repository.findCompleted(eq(2L), eq(at(today)), eq(now), any()))
                .thenReturn(List.of(settlement(1, 500, null, null, now.minusSeconds(1))));
        var expense = mock(PlannedExpense.class);
        when(expense.getExpenseId()).thenReturn(1L);
        when(expense.getTitle()).thenReturn("일회성 실제");
        when(expense.getAmount()).thenReturn(50L);
        when(expense.getExpenseDate()).thenReturn(today.plusDays(3));
        when(expense.getPaidAt()).thenReturn(now.minusSeconds(1));
        when(repository.findExpenses(eq(2L), any(), any(), eq(today), any(), any()))
                .thenReturn(List.of(expense));
        var payment = mock(RecurringExpensePayment.class);
        when(payment.getRecurringExpenseId()).thenReturn(3L);
        when(payment.getOccurrenceDate()).thenReturn(today.plusDays(3));
        when(payment.getPaidAt()).thenReturn(now.minusSeconds(1));
        when(payment.getAmount()).thenReturn(50L);
        when(repository.findPaidRecurring(eq(2L), any(), any(), any())).thenReturn(List.of(payment));
        when(recurring.pendingOccurrencesAsOf(2L, today, today.plusDays(3), now))
                .thenReturn(List.of(
                        RecurringExpenseOccurrence.builder()
                                .recurringExpenseId(3L)
                                .expenseDate(today.plusDays(3))
                                .amount(50L)
                                .paidAt(now.minusSeconds(1))
                                .build(),
                        RecurringExpenseOccurrence.builder()
                                .recurringExpenseId(4L)
                                .expenseDate(today)
                                .amount(200L)
                                .build()));
        var result = service.forecastAt(2L, today, today.plusDays(3), setting, now);
        assertThat(result.currentBalance()).isEqualTo(1800);
        assertThat(result.days().getFirst().closingBalance()).isEqualTo(1600);
        assertThat(result.days().getLast().closingBalance()).isEqualTo(1600);
        assertThat(result.totalExpenses()).isEqualTo(300);
        assertThat(result.days().getFirst().movements()).hasSize(4);
        verify(repository).sumCompleted(2L, base, at(today));
        verify(recurring).pendingOccurrencesAsOf(2L, today, today.plusDays(3), now);
    }

    // 실제 픽업 기준을 우선하고 지난 배치 유입은 잔액에 넣지 않으며 날짜 미정·취소는 별도다.
    @Test
    void forecast_whenScheduledExpectedAndOverdueMixed_classifiesExcludedIncome() {
        when(repository.findScheduled(eq(2L), any(), eq(now), any()))
                .thenReturn(List.of(
                        settlement(
                                1,
                                100,
                                Instant.parse("2026-11-02T18:00:01Z"),
                                Instant.parse("2026-11-01T00:00:00Z"),
                                null),
                        settlement(2, 200, null, Instant.parse("2026-11-02T18:00:00Z"), null),
                        settlement(3, 300, Instant.parse("2026-11-01T18:00:00Z"), null, null)));
        when(repository.sumUndated(2L)).thenReturn(BigDecimal.valueOf(400));
        when(repository.sumCanceled(2L)).thenReturn(BigDecimal.valueOf(500));
        var result = service.forecastAt(2L, today, today.plusDays(2), setting, now);
        assertThat(result.currentBalance()).isEqualTo(1000);
        assertThat(result.days().get(1).expectedIncome()).isEqualTo(200);
        assertThat(result.days().get(2).scheduledIncome()).isEqualTo(100);
        assertThat(result.closingBalance()).isEqualTo(1300);
        assertThat(result.overdueIncome()).isEqualTo(300);
        assertThat(result.undatedIncome()).isEqualTo(400);
        assertThat(result.canceledIncome()).isEqualTo(500);
        assertThat(result.batchEnabled()).isFalse();
    }

    // 미래 조회 시작 전 발생하는 미완료 지출도 예상 시작 잔액에 반영한다.
    @Test
    void forecast_whenRequestedWindowStartsLater_carriesFutureExpenses() {
        when(recurring.pendingOccurrencesAsOf(2L, today, today.plusDays(3), now))
                .thenReturn(List.of(RecurringExpenseOccurrence.builder()
                        .recurringExpenseId(1L)
                        .expenseDate(today.plusDays(1))
                        .amount(1200L)
                        .build()));
        var result = service.forecastAt(2L, today.plusDays(2), today.plusDays(3), setting, now);
        assertThat(result.periodOpeningBalance()).isEqualTo(-200);
        assertThat(result.maximumShortage()).isEqualTo(200);
        assertThat(result.firstShortageDate()).isEqualTo(today.plusDays(2));
        assertThat(result.carryForwardMovements()).hasSize(1);
        assertThat(result.currentBalance()).isEqualTo(1000);
    }

    // 과거 조회에는 당시 실제 내역만 제공하고 정기 예정 회차를 확장하지 않는다.
    @Test
    void forecast_whenHistoricalPeriod_skipsProjectedExpenses() {
        var result = service.forecastAt(2L, today.minusDays(2), today.minusDays(1), setting, now);
        assertThat(result.days()).hasSize(2);
        verifyNoInteractions(recurring);
        verify(repository).findCompleted(eq(2L), eq(at(today.minusDays(2))), eq(at(today)), any());
    }

    // 기준일 이전·너무 먼 미래·367일·역전 기간·가맹점 조작은 조회 전에 거절한다.
    @Test
    void forecast_whenInputInvalid_rejectsBeforeDatabaseReads() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        service.forecastAt(2L, setting.baseDate().minusDays(1), setting.baseDate(), setting, now));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.forecastAt(2L, today, today.plusDays(366), setting, now));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.forecastAt(2L, today.plusDays(366), today.plusDays(366), setting, now));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.forecastAt(2L, today, today.minusDays(1), setting, now));
        assertThatIllegalArgumentException().isThrownBy(() -> service.forecastAt(0L, today, today, setting, now));
        verifyNoInteractions(repository);
    }

    // 최초 잔액이 없으면 임의 0원으로 예측하지 않는다.
    @Test
    void forecast_whenSettingMissing_returns409() {
        assertThatThrownBy(() -> service.forecast(2L, today, today))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        verifyNoInteractions(repository);
    }

    // 대상 행이 한도를 넘으면 일부 결과만으로 합계를 만들지 않는다.
    @Test
    void forecast_whenRowsExceedLimit_returns422() {
        when(repository.findCompleted(eq(2L), any(), any(), any()))
                .thenReturn(Collections.nCopies(10001, settlement(1, 1, null, null, now)));
        assertThatThrownBy(() -> service.forecastAt(2L, today, today, setting, now))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }
    // 첫 DB 조회 뒤로 기준 시각이 움직이면 그 사이 커밋된 기록을 누락하므로 먼저 고정한다.
    @Test
    void forecast_whenSettingsRead_capturesCutoffBeforeFirstDatabaseRead() {
        var firstRead = new java.util.concurrent.atomic.AtomicReference<Instant>();
        when(management.settings(2L)).thenAnswer(invocation -> {
            firstRead.set(Instant.now());
            Thread.sleep(30);
            return Optional.of(setting);
        });
        var day = LocalDate.now(SettlementReportService.ZONE);
        var result = service.forecast(2L, day, day);
        assertThat(result.asOf()).isBeforeOrEqualTo(firstRead.get());
        verify(repository).sumCompleted(2L, at(setting.baseDate()), result.asOf());
    }

    // cutoff 이후 완료가 스냅샷에 보여도 현재 잔액에서 제외한 지출을 예정에서 누락하지 않는다.
    @Test
    void forecast_whenExpensePaidAfterCutoff_keepsItPlanned() {
        var expense = mock(PlannedExpense.class);
        when(expense.getExpenseId()).thenReturn(1L);
        when(expense.getTitle()).thenReturn("조회 직후 완료");
        when(expense.getExpenseDate()).thenReturn(today);
        when(expense.getAmount()).thenReturn(500L);
        when(expense.getPaidAt()).thenReturn(now.plusSeconds(1));
        when(repository.findExpenses(eq(2L), any(), any(), eq(today), eq(today), any()))
                .thenReturn(List.of(expense));
        var result = service.forecastAt(2L, today, today, setting, now);
        assertThat(result.currentBalance()).isEqualTo(1000);
        assertThat(result.days().getFirst().paidExpenses()).isZero();
        assertThat(result.days().getFirst().plannedExpenses()).isEqualTo(500);
        assertThat(result.closingBalance()).isEqualTo(500);
    }
    // 현재 잔액도 유입과 지출을 모두 반영한 식의 최종 값으로 범위를 검사한다.
    @Test
    void forecast_whenActualAmountsOffsetAtSafeLimit_returnsSafeCurrentBalance() {
        when(repository.sumCompleted(eq(2L), eq(at(setting.baseDate())), any())).thenReturn(BigDecimal.ONE);
        when(repository.sumPaidExpenses(eq(2L), eq(at(setting.baseDate())), any()))
                .thenReturn(BigDecimal.ONE);
        var result = service.forecastAt(
                2L, today, today, new CashFlowSettingResponse(9007199254740991L, setting.baseDate()), now);
        assertThat(result.currentBalance()).isEqualTo(9007199254740991L);
        assertThat(result.closingBalance()).isEqualTo(9007199254740991L);
    }
    // 미래 조회 전날 실제 잔액의 미반환 중간 값이 상한을 넘어도 이월 후 시작 잔액을 안전하게 반환한다.
    @Test
    void forecast_whenUnreturnedInitialBalanceExceedsLimit_offsetsCarryBeforeValidation() {
        when(repository.sumCompleted(eq(2L), eq(at(setting.baseDate())), any())).thenReturn(BigDecimal.ONE);
        when(repository.sumPaidExpenses(2L, at(setting.baseDate()), now)).thenReturn(BigDecimal.ONE);
        var expense = mock(PlannedExpense.class);
        when(expense.getExpenseId()).thenReturn(1L);
        when(expense.getExpenseDate()).thenReturn(today);
        when(expense.getAmount()).thenReturn(1L);
        when(expense.getPaidAt()).thenReturn(now.minusSeconds(1));
        when(repository.findExpenses(eq(2L), any(), any(), eq(today), eq(today.plusDays(1)), any()))
                .thenReturn(List.of(expense));
        var result = service.forecastAt(
                2L,
                today.plusDays(1),
                today.plusDays(1),
                new CashFlowSettingResponse(9007199254740991L, setting.baseDate()),
                now);
        assertThat(result.currentBalance()).isEqualTo(9007199254740991L);
        assertThat(result.periodOpeningBalance()).isEqualTo(9007199254740991L);
        assertThat(result.closingBalance()).isEqualTo(9007199254740991L);
        assertThat(result.carryForwardMovements()).hasSize(1);
    }
}
