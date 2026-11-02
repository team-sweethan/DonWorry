package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.settlement.dto.CashFlowForecastResponse.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CashFlowCalculatorTests {
    private final CashFlowCalculator calculator = new CashFlowCalculator();
    private final LocalDate start = LocalDate.of(2026, 11, 2);

    private Movement movement(long id, int offset, long amount, String kind) {
        return Movement.builder()
                .source(kind.equals("PAID") || kind.equals("PLANNED") ? "EXPENSE" : "SETTLEMENT")
                .referenceId(id)
                .date(start.plusDays(offset))
                .amount(amount)
                .classification(kind)
                .title("근거")
                .build();
    }

    // 잔액이 정확히 0인 날은 부족이 아니며 연속 음수 구간과 다시 시작한 구간을 구분한다.
    @Test
    void calculate_whenBalancesCrossZero_returnsSeparateShortagesAndEvidence() {
        var result = calculator.calculate(
                100,
                start,
                start,
                start.plusDays(4),
                List.of(
                        movement(1, 0, 100, "PLANNED"),
                        movement(2, 1, 50, "PLANNED"),
                        movement(3, 2, 25, "PAID"),
                        movement(4, 3, 100, "SCHEDULED"),
                        movement(5, 4, 30, "PLANNED")));
        assertThat(result.days()).extracting(Day::closingBalance).containsExactly(0L, -50L, -75L, 25L, -5L);
        assertThat(result.firstShortageDate()).isEqualTo(start.plusDays(1));
        assertThat(result.lowestBalance()).isEqualTo(-75);
        assertThat(result.maximumShortage()).isEqualTo(75);
        assertThat(result.shortages())
                .containsExactly(
                        new Shortage(start.plusDays(1), start.plusDays(2), 2, 75),
                        new Shortage(start.plusDays(4), start.plusDays(4), 1, 5));
        assertThat(result.days().get(1).movements())
                .extracting(Movement::referenceId)
                .containsExactly(2L);
        assertThat(result.totalIncome()).isEqualTo(100);
        assertThat(result.totalExpenses()).isEqualTo(205);
    }

    // 조회 전 예정 거래는 시작 잔액에 이어 반영하고 응답 기간 합계에는 다시 넣지 않는다.
    @Test
    void calculate_whenFromIsLater_carriesPriorMovementsOnce() {
        var prior = movement(1, 0, 150, "PLANNED");
        var result = calculator.calculate(100, start, start.plusDays(1), start.plusDays(2), List.of(prior));
        assertThat(result.periodOpeningBalance()).isEqualTo(-50);
        assertThat(result.totalExpenses()).isZero();
        assertThat(result.carryForwardMovements()).containsExactly(prior);
        assertThat(result.shortages()).containsExactly(new Shortage(start.plusDays(1), start.plusDays(2), 2, 50));
    }

    // 같은 정산이 중복 입력되면 조용히 두 번 합산하지 않는다.
    @Test
    void calculate_whenSettlementDuplicated_rejects() {
        var row = movement(1, 0, 10, "COMPLETED");
        assertThatIllegalStateException()
                .isThrownBy(() -> calculator.calculate(0, start, start, start, List.of(row, row)));
    }

    // Java long과 브라우저 안전 정수 범위를 넘는 합계는 모두 명시적인 422다.
    @Test
    void money_whenOutsideSafeRange_rejectsInsteadOfWrappingOrRounding() {
        for (var value : List.of(
                new BigDecimal("9223372036854775808"),
                new BigDecimal("9007199254740992"),
                new BigDecimal("-9007199254740992"),
                new BigDecimal("0.5"))) {
            assertThatThrownBy(() -> CashFlowCalculator.money(value))
                    .isInstanceOfSatisfying(
                            SettlementRequestException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(422));
        }
        assertThatThrownBy(() -> calculator.calculate(
                        9007199254740991L, start, start, start, List.of(movement(1, 0, 1, "EXPECTED"))))
                .isInstanceOf(SettlementRequestException.class);
    }

    // 366일은 포함하고 367일은 거절한다.
    @Test
    void calculate_whenPeriodAtBoundary_validatesInclusiveDays() {
        assertThat(calculator
                        .calculate(0, start, start, start.plusDays(365), List.of())
                        .days())
                .hasSize(366);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(0, start, start, start.plusDays(366), List.of()));
    }
    // 같은 날 상쇄되는 1원 입출금은 중간 합이 상한을 넘어도 최종 잔액이 안전하면 허용한다.
    @Test
    void calculate_whenIncomeAndExpenseOffsetAtSafeLimit_returnsFinalSafeBalance() {
        var result = calculator.calculate(
                9007199254740991L,
                start,
                start,
                start,
                List.of(movement(1, 0, 1, "COMPLETED"), movement(2, 0, 1, "PAID")));
        assertThat(result.closingBalance()).isEqualTo(9007199254740991L);
        assertThat(result.totalIncome()).isEqualTo(1);
        assertThat(result.totalExpenses()).isEqualTo(1);
    }
}
