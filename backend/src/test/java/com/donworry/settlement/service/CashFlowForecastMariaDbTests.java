package com.donworry.settlement.service;

import static com.donworry.settlement.UtcJdbcTimestamp.utcTimestamp;
import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.dto.*;
import com.donworry.settlement.repository.CashFlowForecastRepository;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class CashFlowForecastMariaDbTests {
    private static final long MERCHANT = 923001L;
    private static final long PAYMENT = 9230001L;

    @MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    CaptureService captures;

    @Autowired
    CashFlowManagementService management;

    @Autowired
    RecurringExpenseService recurring;

    @Autowired
    CashFlowForecastService forecasts;

    @Autowired
    CashFlowForecastRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private LocalDate today() {
        return LocalDate.now(SettlementReportService.ZONE);
    }

    private Instant at(LocalDate d) {
        return d.atStartOfDay(SettlementReportService.ZONE).toInstant();
    }

    @BeforeEach
    void seed() {
        for (long id : new long[] {MERCHANT, MERCHANT + 1}) {
            jdbc.update(
                    "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,?,'ACTIVE',0.01,UTC_TIMESTAMP(6))",
                    id,
                    "P6-FORECAST-" + id,
                    "forecast test");
        }
        jdbc.update(
                "INSERT INTO cash_flow_settings(merchant_id,opening_balance,base_date,created_at,updated_at) VALUES (?,1000,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                MERCHANT,
                today().minusYears(2));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM recurring_expense_payments WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM recurring_expenses WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM planned_expenses WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM cash_flow_settings WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM settlements WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM captures WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM merchants WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
    }

    private void capture(long payment, long merchant) {
        captures.processCapture(payment, merchant, "P6-FORECAST-" + payment, 10000L);
    }

    private void complete(long payment, Instant at) {
        jdbc.update(
                "UPDATE settlements SET status='COMPLETED',completed_at=? WHERE payment_id=?",
                utcTimestamp(at),
                payment);
    }

    private ExpenseRequest expense(String title, LocalDate date, long amount) {
        return ExpenseRequest.builder()
                .title(title)
                .expenseDate(date)
                .amount(amount)
                .category("MATERIAL")
                .build();
    }

    @Test
    void fixture_whenInstantBound_storesUtcDateTime() {
        capture(PAYMENT, MERCHANT);
        var instant = Instant.parse("2026-11-02T18:00:00.123456Z");
        complete(PAYMENT, instant);
        assertThat(jdbc.queryForObject(
                        "SELECT completed_at FROM settlements WHERE payment_id=?", LocalDateTime.class, PAYMENT))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 18, 0, 0, 123456000));
    }

    // 2년 전 설정·조회 이전 거래를 누적하고 타 가맹점·삭제·연체 예정 지출을 섞지 않는다.
    @Test
    void forecast_whenHistoryAndPrepaidRecurringExist_preservesActualAndMerchantBoundaries() {
        capture(PAYMENT, MERCHANT);
        complete(PAYMENT, at(today().minusDays(400)));
        capture(PAYMENT + 1, MERCHANT + 1);
        complete(PAYMENT + 1, at(today().minusDays(400)));
        var paid = management.createExpense(MERCHANT, expense("실제 지출", today().plusDays(2), 500));
        management.completeExpense(MERCHANT, paid.expenseId());
        management.createExpense(MERCHANT, expense("내일 지출", today().plusDays(1), 1000));
        management.createExpense(MERCHANT, expense("연체 미완료", today().minusDays(1), 100));
        var deleted = management.createExpense(MERCHANT, expense("삭제", today(), 700));
        management.deleteExpense(MERCHANT, deleted.expenseId());
        var rule = recurring.create(
                MERCHANT,
                RecurringExpenseRequest.builder()
                        .title("미리 지출")
                        .amount(300L)
                        .category("MATERIAL")
                        .frequency("DAILY")
                        .startDate(today().plusDays(2))
                        .endDate(today().plusDays(2))
                        .build());
        recurring.complete(MERCHANT, rule.recurringExpenseId(), today().plusDays(2));
        recurring.stop(MERCHANT, rule.recurringExpenseId());
        // 같은 시스템 시각 tick의 완료가 조회 cutoff와 같아지는 실행 환경 차이를 제거한다.
        jdbc.update(
                "UPDATE planned_expenses SET paid_at=? WHERE merchant_id=? AND paid_at IS NOT NULL",
                utcTimestamp(at(today())),
                MERCHANT);
        jdbc.update(
                "UPDATE recurring_expense_payments SET paid_at=? WHERE merchant_id=?",
                utcTimestamp(at(today())),
                MERCHANT);
        var result = forecasts.forecast(MERCHANT, today(), today().plusDays(2));
        assertThat(result.currentBalance()).isEqualTo(9900);
        assertThat(result.periodOpeningBalance()).isEqualTo(10700);
        assertThat(result.paidExpensesSinceBase()).isEqualTo(800);
        assertThat(result.days().getFirst().paidExpenses()).isEqualTo(800);
        assertThat(result.days().getLast().plannedExpenses()).isZero();
        assertThat(result.closingBalance()).isEqualTo(8900);
        assertThat(result.totalIncome()).isZero();
        var historical = forecasts.forecast(MERCHANT, today().minusDays(400), today().minusDays(400));
        assertThat(historical.periodOpeningBalance()).isEqualTo(1000);
        assertThat(historical.totalIncome()).isEqualTo(9700);
        assertThat(historical.closingBalance()).isEqualTo(10700);
    }

    // 실제 픽업 기준·예상 기준·연체·미정·취소를 분리하고 03시 이후 자격은 다음 날로 넘긴다.
    @Test
    void forecast_whenSettlementStatesMixed_usesBatchDatesAndSeparateExclusions() {
        for (int i = 0; i < 5; i++) capture(PAYMENT + i, MERCHANT);
        var batch = at(today().plusDays(1)).plusSeconds(3 * 3600);
        jdbc.update(
                "UPDATE settlements SET scheduled_at=?,expected_settlement_at=? WHERE payment_id=?",
                utcTimestamp(batch.plusSeconds(1)),
                utcTimestamp(batch.minusSeconds(86400)),
                PAYMENT);
        jdbc.update(
                "UPDATE settlements SET scheduled_at=NULL,expected_settlement_at=? WHERE payment_id=?",
                utcTimestamp(batch),
                PAYMENT + 1);
        jdbc.update(
                "UPDATE settlements SET scheduled_at=? WHERE payment_id=?",
                utcTimestamp(at(today().minusDays(1))),
                PAYMENT + 2);
        // 신규 매입은 D+3 일정이 있으므로 과거 호환의 날짜 미정 자료를 명시적으로 만든다.
        jdbc.update(
                "UPDATE settlements SET scheduled_at=NULL,expected_settlement_at=NULL WHERE payment_id=?", PAYMENT + 3);
        jdbc.update("UPDATE settlements SET status='CANCELED' WHERE payment_id=?", PAYMENT + 4);
        var result = forecasts.forecast(MERCHANT, today(), today().plusDays(2));
        assertThat(result.days().get(1).expectedIncome()).isEqualTo(9700);
        assertThat(result.days().get(2).scheduledIncome()).isEqualTo(9700);
        assertThat(result.overdueIncome()).isEqualTo(9700);
        assertThat(result.undatedIncome()).isEqualTo(9700);
        assertThat(result.canceledIncome()).isEqualTo(9700);
        assertThat(result.closingBalance()).isEqualTo(20400);
        assertThat(result.currentBalance()).isEqualTo(1000);
        assertThat(forecasts
                        .forecast(MERCHANT, today().plusDays(1), today().plusDays(1))
                        .closingBalance())
                .isEqualTo(10700);
    }

    // SQL 합계가 BIGINT를 넘어도 DECIMAL로 읽고 잘못된 작은 잔액 대신 422를 반환한다.
    @Test
    void forecast_whenSqlSumExceedsLong_returnsExplicitOverflow() {
        capture(PAYMENT, MERCHANT);
        capture(PAYMENT + 1, MERCHANT);
        complete(PAYMENT, at(today().minusDays(1)));
        complete(PAYMENT + 1, at(today().minusDays(1)));
        jdbc.update(
                "UPDATE settlements SET settlement_amount=9223372036854775807 WHERE payment_id IN (?,?)",
                PAYMENT,
                PAYMENT + 1);
        assertThat(repository.sumCompleted(MERCHANT, at(today().minusYears(2)), Instant.now()))
                .isEqualByComparingTo(new BigDecimal("18446744073709551614"));
        assertThatThrownBy(() -> forecasts.forecast(MERCHANT, today(), today()))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }
    // 완료·예정 조회에 사용할 복합 인덱스를 migration이 실제 DB에 등록한다.
    @Test
    void forecastIndexes_whenMigrationsApplied_supportMerchantScopedQueries() {
        var columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='settlements' AND INDEX_NAME='idx_settlements_merchant_completed' ORDER BY SEQ_IN_INDEX",
                String.class);
        assertThat(columns).containsExactly("merchant_id", "status", "completed_at", "settlement_amount");
        var scheduled = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='settlements' AND INDEX_NAME='idx_settlements_merchant_scheduled' ORDER BY SEQ_IN_INDEX",
                String.class);
        assertThat(scheduled).containsExactly("merchant_id", "status", "scheduled_at", "expected_settlement_at");
        capture(PAYMENT, MERCHANT);
        complete(PAYMENT, at(today().minusDays(1)));
        var plan = jdbc.queryForMap(
                "EXPLAIN SELECT SUM(settlement_amount) FROM settlements WHERE merchant_id=? AND status='COMPLETED' AND completed_at>=? AND completed_at<?",
                MERCHANT,
                utcTimestamp(at(today().minusYears(2))),
                utcTimestamp(Instant.now()));
        assertThat(String.valueOf(plan.get("possible_keys"))).contains("idx_settlements_merchant_completed");
    }
    // 기준 시각 직후 완료된 기록을 스냅샷이 보더라도 실제와 예정 양쪽에서 누락하지 않는다.
    @Test
    void forecast_whenCompletionAfterCutoff_keepsIncomeAndExpensesProjected() {
        var cutoff = Instant.now().minusSeconds(2);
        var day = cutoff.atZone(SettlementReportService.ZONE).toLocalDate();
        var expense = management.createExpense(MERCHANT, expense("동시 완료", today(), 500));
        management.completeExpense(MERCHANT, expense.expenseId());
        var rule = recurring.create(
                MERCHANT,
                RecurringExpenseRequest.builder()
                        .title("동시 정기 완료")
                        .amount(300L)
                        .category("MATERIAL")
                        .frequency("DAILY")
                        .startDate(today())
                        .endDate(today())
                        .build());
        recurring.complete(MERCHANT, rule.recurringExpenseId(), today());
        capture(PAYMENT, MERCHANT);
        jdbc.update(
                "UPDATE settlements SET scheduled_at=?,status='COMPLETED',completed_at=? WHERE payment_id=?",
                utcTimestamp(cutoff.plusMillis(500)),
                utcTimestamp(cutoff.plusSeconds(1)),
                PAYMENT);
        var result = forecasts.forecastAt(
                MERCHANT, day, today().plusDays(1), new CashFlowSettingResponse(1000L, today().minusYears(2)), cutoff);
        assertThat(result.currentBalance()).isEqualTo(1000);
        assertThat(result.paidExpensesSinceBase()).isZero();
        assertThat(result.totalExpenses()).isEqualTo(800);
        assertThat(result.days())
                .extracting(CashFlowForecastResponse.Day::paidExpenses)
                .containsOnly(0L);
        assertThat(result.totalIncome()).isEqualTo(9700);
        assertThat(result.closingBalance()).isEqualTo(9900);
    }
}
