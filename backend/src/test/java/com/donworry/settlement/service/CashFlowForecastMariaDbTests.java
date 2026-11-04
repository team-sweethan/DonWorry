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

    @Autowired
    RefundAdjustmentService refunds;

    @Autowired
    SettlementCompletionTransactionService completion;

    @Autowired
    SettlementReportService reports;

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
        jdbc.update(
                "DELETE x FROM payout_refund_allocations x JOIN merchant_payouts p ON p.payout_id=x.payout_id WHERE p.merchant_id IN (?,?)",
                MERCHANT,
                MERCHANT + 1);
        jdbc.update("DELETE FROM settlement_refund_adjustments WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update(
                "DELETE i FROM merchant_payout_items i JOIN merchant_payouts p ON p.payout_id=i.payout_id WHERE p.merchant_id IN (?,?)",
                MERCHANT,
                MERCHANT + 1);
        jdbc.update("DELETE FROM merchant_payouts WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM settlements WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM captures WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM merchant_settlement_locks WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
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
        long merchant =
                jdbc.queryForObject("SELECT merchant_id FROM settlements WHERE payment_id=?", Long.class, payment);
        jdbc.update(
                """
                INSERT INTO merchant_payouts(merchant_id,payout_date,status,origin,settlement_total,refund_deduction_total,payout_amount,completed_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','BATCH',9700,0,9700,?,?,?)
                ON DUPLICATE KEY UPDATE settlement_total=settlement_total+9700,payout_amount=payout_amount+9700
                """,
                merchant,
                at.atZone(SettlementReportService.ZONE).toLocalDate(),
                utcTimestamp(at),
                utcTimestamp(at),
                utcTimestamp(at));
        jdbc.update(
                """
                INSERT INTO merchant_payout_items(payout_id,settlement_id,settlement_amount,created_at)
                SELECT p.payout_id,s.settlement_id,9700,? FROM settlements s JOIN merchant_payouts p
                ON p.merchant_id=s.merchant_id AND p.payout_date=? WHERE s.payment_id=?
                """, utcTimestamp(at), at.atZone(SettlementReportService.ZONE).toLocalDate(), payment);
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
        complete(PAYMENT + 1, at(today().minusDays(2)));
        jdbc.update(
                "UPDATE settlements SET settlement_amount=9223372036854775807 WHERE payment_id IN (?,?)",
                PAYMENT,
                PAYMENT + 1);
        jdbc.update(
                "UPDATE merchant_payouts SET settlement_total=9223372036854775807,payout_amount=9223372036854775807 WHERE merchant_id=?",
                MERCHANT);
        assertThat(repository.sumPaidOut(MERCHANT, at(today().minusYears(2)), Instant.now()))
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

    private com.donworry.settlement.entity.SettlementRefundAdjustment refund(long payment) {
        long capture = jdbc.queryForObject("SELECT capture_id FROM captures WHERE payment_id=?", Long.class, payment);
        return refunds.record(com.donworry.settlement.api.PaymentRefundSucceeded.builder()
                .cancellationId(payment)
                .paymentId(payment)
                .captureId(capture)
                .merchantId(MERCHANT)
                .cancellationAmount(10000L)
                .canceledAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                .build());
    }

    private void schedule(long payment, LocalDate date) {
        jdbc.update(
                "UPDATE settlements SET scheduled_at=?,expected_settlement_at=? WHERE payment_id=?",
                utcTimestamp(at(date)),
                utcTimestamp(at(date)),
                payment);
    }

    // 회수 중인 환불은 과거 실제 입금을 취소하지 않고 미래 순유입만 줄인다.
    @Test
    void forecast_whenPaidRefundAndFutureIncomeExist_deductsAcrossDatesAndPreservesHistory() {
        capture(PAYMENT, MERCHANT);
        complete(PAYMENT, at(today().minusDays(1)));
        refund(PAYMENT);
        capture(PAYMENT + 1, MERCHANT);
        schedule(PAYMENT + 1, today().plusDays(1));
        capture(PAYMENT + 2, MERCHANT);
        schedule(PAYMENT + 2, today().plusDays(2));
        var before = jdbc.queryForList("SELECT * FROM merchant_payouts WHERE merchant_id=?", MERCHANT);
        var result = forecasts.forecast(MERCHANT, today().plusDays(2), today().plusDays(2));
        assertThat(result.currentBalance()).isEqualTo(10700);
        assertThat(result.periodOpeningBalance()).isEqualTo(10700);
        assertThat(result.totalIncome()).isEqualTo(9700);
        assertThat(result.closingBalance()).isEqualTo(20400);
        assertThat(result.outstandingRecoveryAmount()).isEqualTo(9700);
        assertThat(result.projectedRefundDeduction()).isEqualTo(9700);
        assertThat(result.remainingRecoveryAmount()).isZero();
        assertThat(result.carryForwardMovements()).singleElement().satisfies(m -> {
            assertThat(m.amount()).isZero();
            assertThat(m.settlementAmount()).isEqualTo(9700);
            assertThat(m.refundDeductionAmount()).isEqualTo(9700);
        });
        assertThat(jdbc.queryForList("SELECT * FROM merchant_payouts WHERE merchant_id=?", MERCHANT))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payout_refund_allocations x JOIN merchant_payouts p ON p.payout_id=x.payout_id WHERE p.merchant_id=?",
                        Long.class,
                        MERCHANT))
                .isZero();
        assertThat(repository.outstandingRecovery(MERCHANT + 1, Instant.now())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // 실제 배치 차감액과 조회·잔액이 일치하고 원 정산금을 다시 입금으로 합산하지 않는다.
    @Test
    void forecast_whenBatchPayoutFullyOffsetsDebt_returnsZeroActualCashWithEvidence() {
        capture(PAYMENT, MERCHANT);
        complete(PAYMENT, at(today().minusDays(1)));
        refund(PAYMENT);
        capture(PAYMENT + 1, MERCHANT);
        jdbc.update(
                "UPDATE captures SET captured_at=? WHERE payment_id=?",
                utcTimestamp(at(today().minusDays(7))),
                PAYMENT + 1);
        schedule(PAYMENT + 1, today().minusDays(1));
        long upper = jdbc.queryForObject(
                "SELECT MAX(settlement_id) FROM settlements WHERE merchant_id=?", Long.class, MERCHANT);
        assertThat(completion.completeMerchant(MERCHANT, Instant.now(), upper)).isEqualTo("COMPLETED");
        // 완료와 조회가 같은 clock tick이면 cutoff 경계로 제외되므로 완료를 명확히 이전에 둔다.
        var done = Instant.now().minusSeconds(1);
        jdbc.update(
                "UPDATE merchant_payouts SET completed_at=? WHERE merchant_id=? AND payout_date=?",
                utcTimestamp(done),
                MERCHANT,
                today());
        jdbc.update("UPDATE settlements SET completed_at=? WHERE payment_id=?", utcTimestamp(done), PAYMENT + 1);
        var result = forecasts.forecast(MERCHANT, today(), today());
        assertThat(result.currentBalance()).isEqualTo(10700);
        assertThat(result.totalIncome()).isZero();
        assertThat(result.days().getFirst().movements()).singleElement().satisfies(m -> {
            assertThat(m.source()).isEqualTo("PAYOUT");
            assertThat(m.amount()).isZero();
            assertThat(m.refundDeductionAmount()).isEqualTo(9700);
        });
        assertThat(result.outstandingRecoveryAmount()).isZero();
        var report = reports.report(MERCHANT, today(), today());
        assertThat(report.completedAmount()).isEqualTo(9700);
        assertThat(report.paidSettlementAmount()).isEqualTo(9700);
        assertThat(report.refundDeductionAmount()).isEqualTo(9700);
        assertThat(report.paidAmount()).isZero();
        assertThat(report.days().getFirst().paidAmount()).isZero();
        assertThat(report.payouts())
                .singleElement()
                .satisfies(p -> assertThat(p.payoutAmount()).isZero());
        var history = reports.report(MERCHANT, today().minusDays(1), today().minusDays(1));
        assertThat(history.paidAmount()).isEqualTo(9700);
        assertThat(history.refundDeductionAmount()).isZero();
    }

    // 기준 시각 후 회수가 보이는 스냅샷에서도 기준 시각 전의 미회수액을 복원한다.
    @Test
    void outstandingRecovery_whenAllocationAfterCutoff_restoresEarlierDebt() {
        capture(PAYMENT, MERCHANT);
        complete(PAYMENT, at(today().minusDays(2)));
        var adjustment = refund(PAYMENT);
        var cutoff = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update(
                "UPDATE settlement_refund_adjustments SET created_at=? WHERE adjustment_id=?",
                utcTimestamp(cutoff.minusSeconds(1)),
                adjustment.getAdjustmentId());
        capture(PAYMENT + 1, MERCHANT);
        complete(PAYMENT + 1, cutoff.plusSeconds(1));
        long payout = jdbc.queryForObject(
                "SELECT payout_id FROM merchant_payouts WHERE merchant_id=? AND payout_date=?",
                Long.class,
                MERCHANT,
                today());
        jdbc.update(
                "UPDATE merchant_payouts SET refund_deduction_total=9700,payout_amount=0 WHERE payout_id=?", payout);
        jdbc.update(
                "INSERT INTO payout_refund_allocations(payout_id,adjustment_id,applied_amount,created_at) VALUES (?,?,9700,?)",
                payout,
                adjustment.getAdjustmentId(),
                utcTimestamp(cutoff.plusSeconds(1)));
        jdbc.update(
                "UPDATE settlement_refund_adjustments SET remaining_amount=0 WHERE adjustment_id=?",
                adjustment.getAdjustmentId());
        assertThat(repository.outstandingRecovery(MERCHANT, cutoff)).isEqualByComparingTo("9700");
        assertThat(repository.outstandingRecovery(MERCHANT, cutoff.plusSeconds(1)))
                .isEqualByComparingTo("9700");
        assertThat(repository.outstandingRecovery(MERCHANT, cutoff.plusSeconds(2)))
                .isEqualByComparingTo("0");
        assertThat(repository.sumPaidOut(MERCHANT, at(today()), cutoff)).isEqualByComparingTo("0");
    }

    // 이전 버전의 미적용 미지급 환불은 유입 예측에서 제외하고 지급 후 미적용 환불은 채무로 잡는다.
    @Test
    void forecast_whenPendingRefundsExist_excludesUnpaidAndCountsPaidDebt() {
        capture(PAYMENT, MERCHANT);
        complete(PAYMENT, at(today().minusDays(1)));
        refund(PAYMENT);
        capture(PAYMENT + 1, MERCHANT);
        schedule(PAYMENT + 1, today().plusDays(1));
        refund(PAYMENT + 1);
        jdbc.update(
                "UPDATE settlement_refund_adjustments SET status='PENDING',disposition=NULL,remaining_amount=adjustment_amount WHERE merchant_id=?",
                MERCHANT);
        jdbc.update("UPDATE settlements SET status='SCHEDULED' WHERE payment_id=?", PAYMENT + 1);
        var result = forecasts.forecast(MERCHANT, today(), today().plusDays(2));
        assertThat(result.totalIncome()).isZero();
        assertThat(result.outstandingRecoveryAmount()).isEqualTo(9700);
        assertThat(result.remainingRecoveryAmount()).isEqualTo(9700);
        assertThat(result.closingBalance()).isEqualTo(10700);
    }
}
