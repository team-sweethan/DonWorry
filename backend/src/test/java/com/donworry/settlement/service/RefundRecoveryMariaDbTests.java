package com.donworry.settlement.service;

import static com.donworry.settlement.UtcJdbcTimestamp.utcTimestamp;
import static org.assertj.core.api.Assertions.*;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// 기존 환불 통합 테스트의 Nested로만 실행해 전체 앱 컨텍스트를 추가로 만들지 않는다.
abstract class RefundRecoveryMariaDbTests {
    private static final long MERCHANT = 940501, PAYMENT = 9405001, CANCELLATION = 9405101;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CaptureRepository captures;

    @Autowired
    SettlementRepository settlements;

    @Autowired
    MerchantPayoutRepository payouts;

    @Autowired
    SettlementRefundAdjustmentRepository adjustments;

    @Autowired
    SettlementService creation;

    @Autowired
    SettlementCompletionTransactionService completion;

    @Autowired
    RefundAdjustmentService refunds;

    private Capture source;
    private long originalPayout;

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    @BeforeEach
    void seed() {
        for (long id : new long[] {MERCHANT, MERCHANT + 1})
            jdbc.update(
                    "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,?,'ACTIVE',0.01,UTC_TIMESTAMP(6))",
                    id,
                    "P6-RECOVERY-" + id,
                    "recovery test");
        source = create(PAYMENT, MERCHANT, 10000);
        assertThat(pay(MERCHANT)).isEqualTo("COMPLETED");
        originalPayout = currentPayout(MERCHANT).getPayoutId();
        backdate(originalPayout, 3);
    }

    private Capture create(long payment, long merchant, long amount) {
        var at = Instant.now().minusSeconds(864000).truncatedTo(ChronoUnit.MICROS);
        var c = Capture.processing(payment, merchant, "P6-RECOVERY-" + payment, amount, at);
        c.complete(at);
        c = captures.save(c);
        creation.createSettlement(c);
        return c;
    }

    private String pay(long merchant) {
        return completion.completeMerchant(merchant, Instant.now(), Long.MAX_VALUE);
    }

    private MerchantPayout currentPayout(long merchant) {
        return payouts.findByMerchantIdAndPayoutDate(merchant, today()).orElseThrow();
    }

    private PaymentRefundSucceeded event(long cancellation, Capture capture) {
        return new PaymentRefundSucceeded(
                cancellation,
                capture.getPaymentId(),
                capture.getCaptureId(),
                capture.getMerchantId(),
                capture.getCaptureAmount(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private void backdate(long payout, int days) {
        var date = today().minusDays(days);
        var at = date.atTime(3, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        jdbc.update(
                "UPDATE merchant_payouts SET payout_date=?,completed_at=? WHERE payout_id=?",
                date,
                utcTimestamp(at),
                payout);
        jdbc.update(
                "UPDATE settlements s JOIN merchant_payout_items i ON i.settlement_id=s.settlement_id SET s.completed_at=? WHERE i.payout_id=?",
                utcTimestamp(at),
                payout);
    }

    private long remaining(long cancellation) {
        return adjustments.findByCancellationId(cancellation).orElseThrow().getRemainingAmount();
    }

    private long allocations() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM payout_refund_allocations x JOIN settlement_refund_adjustments a ON a.adjustment_id=x.adjustment_id WHERE a.merchant_id=?",
                Long.class,
                MERCHANT);
    }

    private void conserved(long cancellation) {
        var a = adjustments.findByCancellationId(cancellation).orElseThrow();
        var sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(applied_amount),0) FROM payout_refund_allocations WHERE adjustment_id=?",
                BigDecimal.class,
                a.getAdjustmentId());
        assertThat(sum.add(BigDecimal.valueOf(a.getRemainingAmount())))
                .isEqualByComparingTo(BigDecimal.valueOf(a.getAdjustmentAmount()));
    }

    @AfterEach
    void clean() {
        for (long merchant : new long[] {MERCHANT, MERCHANT + 1}) {
            jdbc.update(
                    "DELETE x FROM payout_refund_allocations x JOIN settlement_refund_adjustments a ON a.adjustment_id=x.adjustment_id WHERE a.merchant_id=?",
                    merchant);
            jdbc.update(
                    "DELETE i FROM merchant_payout_items i JOIN merchant_payouts p ON p.payout_id=i.payout_id WHERE p.merchant_id=?",
                    merchant);
            jdbc.update("DELETE FROM settlement_refund_adjustments WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM merchant_payouts WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM settlements WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM captures WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM merchant_settlement_locks WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchant);
        }
    }

    // 고객 환불 전액과 회수 순정산액을 구분하고 과거 지급·정산·매입을 보존한다.
    @Test
    void recover_whenIncomeSufficient_preservesSourcesAndPaysNetCashOnce() {
        var oldPayout = jdbc.queryForMap("SELECT * FROM merchant_payouts WHERE payout_id=?", originalPayout);
        var oldSettlement = jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", PAYMENT);
        var oldCapture = jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", PAYMENT);
        var e = event(CANCELLATION, source);
        refunds.record(e);
        create(PAYMENT + 1, MERCHANT, 20000);
        assertThat(pay(MERCHANT)).isEqualTo("COMPLETED");
        var p = currentPayout(MERCHANT);
        assertThat(p.getSettlementTotal()).isEqualTo(19400);
        assertThat(p.getRefundDeductionTotal()).isEqualTo(9700);
        assertThat(p.getPayoutAmount()).isEqualTo(9700);
        assertThat(remaining(CANCELLATION)).isZero();
        assertThat(allocations()).isEqualTo(1);
        conserved(CANCELLATION);
        assertThat(pay(MERCHANT)).isEqualTo("ALREADY_COMPLETED");
        refunds.record(e);
        assertThat(allocations()).isEqualTo(1);
        assertThat(remaining(CANCELLATION)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM merchant_payouts WHERE payout_id=?", originalPayout))
                .isEqualTo(oldPayout);
        assertThat(jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", PAYMENT))
                .isEqualTo(oldSettlement);
        assertThat(jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", PAYMENT))
                .isEqualTo(oldCapture);
    }

    // 지급보다 큰 회수액은 음수 지급을 만들지 않고 다음 지급으로 이월한다.
    @Test
    void recover_whenIncomeInsufficient_carriesRemainderAcrossPayouts() {
        var e = event(CANCELLATION, source);
        refunds.record(e);
        create(PAYMENT + 1, MERCHANT, 4000);
        pay(MERCHANT);
        var first = currentPayout(MERCHANT);
        assertThat(first.getPayoutAmount()).isZero();
        assertThat(first.getRefundDeductionTotal()).isEqualTo(3880);
        assertThat(remaining(CANCELLATION)).isEqualTo(5820);
        conserved(CANCELLATION);
        refunds.record(e);
        assertThat(remaining(CANCELLATION)).isEqualTo(5820);
        backdate(first.getPayoutId(), 1);
        create(PAYMENT + 2, MERCHANT, 10000);
        pay(MERCHANT);
        var second = currentPayout(MERCHANT);
        assertThat(second.getRefundDeductionTotal()).isEqualTo(5820);
        assertThat(second.getPayoutAmount()).isEqualTo(3880);
        assertThat(remaining(CANCELLATION)).isZero();
        assertThat(allocations()).isEqualTo(2);
        conserved(CANCELLATION);
    }

    // 지급할 거래가 없으면 빈 지급·배분을 만들지 않고 회수 잔액을 유지한다.
    @Test
    void recover_whenNoIncome_keepsDebtWithoutEmptyPayout() {
        refunds.record(event(CANCELLATION, source));
        assertThat(pay(MERCHANT)).isEqualTo("EMPTY");
        assertThat(payouts.findByMerchantIdAndPayoutDate(MERCHANT, today())).isEmpty();
        assertThat(remaining(CANCELLATION)).isEqualTo(9700);
        assertThat(allocations()).isZero();
    }

    // 같은 가맹점의 회수는 수신 원장 ID 순서로 배분해 순서 역전에도 재현 가능하게 한다.
    @Test
    void recover_whenSeveralRefundsExist_appliesFifoWithinIncome() {
        var second = create(PAYMENT + 1, MERCHANT, 10000);
        pay(MERCHANT);
        backdate(currentPayout(MERCHANT).getPayoutId(), 2);
        refunds.record(event(CANCELLATION, source));
        refunds.record(event(CANCELLATION + 1, second));
        create(PAYMENT + 2, MERCHANT, 15000);
        pay(MERCHANT);
        assertThat(currentPayout(MERCHANT).getRefundDeductionTotal()).isEqualTo(14550);
        assertThat(currentPayout(MERCHANT).getPayoutAmount()).isZero();
        assertThat(remaining(CANCELLATION)).isZero();
        assertThat(remaining(CANCELLATION + 1)).isEqualTo(4850);
        conserved(CANCELLATION);
        conserved(CANCELLATION + 1);
    }

    // 당일 지급 마감 후 받은 환불은 이미 완료한 지급을 고치지 않고 후속 지급에서 회수한다.
    @Test
    void recover_whenRefundArrivesAfterDailyPayout_waitsForNextPayout() {
        create(PAYMENT + 1, MERCHANT, 10000);
        pay(MERCHANT);
        var closed = currentPayout(MERCHANT);
        refunds.record(event(CANCELLATION, source));
        assertThat(pay(MERCHANT)).isEqualTo("ALREADY_COMPLETED");
        assertThat(allocations()).isZero();
        assertThat(payouts.findById(closed.getPayoutId()).orElseThrow().getPayoutAmount())
                .isEqualTo(9700);
        backdate(closed.getPayoutId(), 1);
        create(PAYMENT + 2, MERCHANT, 10000);
        pay(MERCHANT);
        assertThat(currentPayout(MERCHANT).getPayoutAmount()).isZero();
        assertThat(remaining(CANCELLATION)).isZero();
    }

    // 이전 회수로 현금 지급이 0이 된 매출도 나중에 환불되면 그 순정산액을 회수한다.
    @Test
    void recover_whenRefundedSaleWasOffset_preservesCreditConsumptionHistory() {
        refunds.record(event(CANCELLATION, source));
        var second = create(PAYMENT + 1, MERCHANT, 10000);
        pay(MERCHANT);
        var zeroPayout = currentPayout(MERCHANT);
        assertThat(zeroPayout.getPayoutAmount()).isZero();
        backdate(zeroPayout.getPayoutId(), 1);
        refunds.record(event(CANCELLATION + 1, second));
        create(PAYMENT + 2, MERCHANT, 10000);
        pay(MERCHANT);
        assertThat(currentPayout(MERCHANT).getPayoutAmount()).isZero();
        assertThat(remaining(CANCELLATION)).isZero();
        assertThat(remaining(CANCELLATION + 1)).isZero();
        conserved(CANCELLATION);
        conserved(CANCELLATION + 1);
    }

    // 이전 버전의 지급 후 PENDING도 새 지급을 만들기 전에 분류하고 회수한다.
    @Test
    void recover_whenLegacyPendingExists_registersBeforeDeducting() {
        var e = event(CANCELLATION, source);
        adjustments.save(SettlementRefundAdjustment.received(
                e, settlements.findByPaymentId(PAYMENT).orElseThrow(), Instant.now()));
        create(PAYMENT + 1, MERCHANT, 20000);
        pay(MERCHANT);
        var a = adjustments.findByCancellationId(CANCELLATION).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(RefundAdjustmentStatus.APPLIED);
        assertThat(a.getDisposition()).isEqualTo(RefundAdjustmentDisposition.RECOVERY);
        assertThat(a.getRemainingAmount()).isZero();
        assertThat(currentPayout(MERCHANT).getPayoutAmount()).isEqualTo(9700);
    }

    // 가맹점의 다른 거래 수익을 타 가맹점의 환불 회수에 사용하지 않는다.
    @Test
    void recover_whenAnotherMerchantPays_keepsMerchantDebtIsolated() {
        refunds.record(event(CANCELLATION, source));
        create(PAYMENT + 1, MERCHANT + 1, 20000);
        pay(MERCHANT + 1);
        assertThat(currentPayout(MERCHANT + 1).getPayoutAmount()).isEqualTo(19400);
        assertThat(remaining(CANCELLATION)).isEqualTo(9700);
        assertThat(allocations()).isZero();
    }

    // 완료 표시만 있고 지급 근거가 없으면 회수 등록으로 오류를 숨기지 않는다.
    @Test
    void record_whenOriginalPayoutProofMissing_rejectsWithoutAdjustment() {
        jdbc.update("DELETE FROM merchant_payout_items WHERE payout_id=?", originalPayout);
        jdbc.update("DELETE FROM merchant_payouts WHERE payout_id=?", originalPayout);
        assertThatThrownBy(() -> refunds.record(event(CANCELLATION, source))).isInstanceOf(IllegalStateException.class);
        assertThat(adjustments.findByCancellationId(CANCELLATION)).isEmpty();
    }

    // 금액이 다른 지급 상세도 환불 성공 근거로 인정하지 않는다.
    @Test
    void record_whenOriginalPayoutItemAmountDiffers_rejects() {
        jdbc.update(
                "UPDATE merchant_payout_items SET settlement_amount=settlement_amount+1 WHERE payout_id=?",
                originalPayout);
        assertThatThrownBy(() -> refunds.record(event(CANCELLATION, source))).isInstanceOf(IllegalStateException.class);
        assertThat(adjustments.findByCancellationId(CANCELLATION)).isEmpty();
    }

    // 배분 INSERT 또는 최종 지급 UPDATE 실패는 배분·잔액·신규 지급·정산 변경을 함께 롤백한다.
    @ParameterizedTest
    @ValueSource(strings = {"allocation", "payout"})
    void recover_whenDatabaseWriteFails_rollsBackAllFinancialChanges(String location) {
        var a = refunds.record(event(CANCELLATION, source));
        var income = create(PAYMENT + 1, MERCHANT, 20000);
        String trigger = location.equals("allocation")
                ? "CREATE TRIGGER p6_recovery_failure BEFORE INSERT ON payout_refund_allocations FOR EACH ROW BEGIN IF NEW.adjustment_id="
                        + a.getAdjustmentId()
                        + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test allocation failure'; END IF; END"
                : "CREATE TRIGGER p6_recovery_failure BEFORE UPDATE ON merchant_payouts FOR EACH ROW BEGIN IF NEW.merchant_id="
                        + MERCHANT
                        + " AND NEW.status='COMPLETED' AND OLD.status='PENDING' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test payout failure'; END IF; END";
        jdbc.execute(trigger);
        try {
            assertThatThrownBy(() -> pay(MERCHANT)).isInstanceOf(RuntimeException.class);
            assertThat(remaining(CANCELLATION)).isEqualTo(9700);
            assertThat(allocations()).isZero();
            assertThat(payouts.findByMerchantIdAndPayoutDate(MERCHANT, today())).isEmpty();
            assertThat(settlements
                            .findByCaptureId(income.getCaptureId())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(SettlementStatus.SCHEDULED);
        } finally {
            jdbc.execute("DROP TRIGGER p6_recovery_failure");
        }
        assertThat(pay(MERCHANT)).isEqualTo("COMPLETED");
        assertThat(remaining(CANCELLATION)).isZero();
        conserved(CANCELLATION);
    }

    // 총 회수액이 Long을 넘어도 개별 잔액에서 이번 지급 한도만 안전하게 회수한다.
    @Test
    void recover_whenAggregateDebtExceedsLong_deductsOnlyBoundedIncome() {
        var first = create(PAYMENT + 1, MERCHANT, Long.MAX_VALUE);
        pay(MERCHANT);
        backdate(currentPayout(MERCHANT).getPayoutId(), 2);
        var second = create(PAYMENT + 2, MERCHANT, Long.MAX_VALUE);
        pay(MERCHANT);
        backdate(currentPayout(MERCHANT).getPayoutId(), 1);
        refunds.record(event(CANCELLATION, first));
        refunds.record(event(CANCELLATION + 1, second));
        create(PAYMENT + 3, MERCHANT, 10000);
        pay(MERCHANT);
        assertThat(currentPayout(MERCHANT).getRefundDeductionTotal()).isEqualTo(9700);
        assertThat(currentPayout(MERCHANT).getPayoutAmount()).isZero();
        var debt = jdbc.queryForObject(
                "SELECT SUM(remaining_amount) FROM settlement_refund_adjustments WHERE merchant_id=?",
                BigDecimal.class,
                MERCHANT);
        assertThat(debt).isGreaterThan(BigDecimal.valueOf(Long.MAX_VALUE));
        conserved(CANCELLATION);
        conserved(CANCELLATION + 1);
    }

    // 회수할 순정산액이 0인 환불은 0원 배분을 만들지 않는다.
    @Test
    void recover_whenOriginalNetIsZero_keepsZeroDebtWithoutAllocation() {
        jdbc.update("UPDATE merchants SET pg_fee_rate=0.98 WHERE merchant_id=?", MERCHANT);
        var zero = create(PAYMENT + 1, MERCHANT, 10000);
        pay(MERCHANT);
        backdate(currentPayout(MERCHANT).getPayoutId(), 1);
        refunds.record(event(CANCELLATION, zero));
        jdbc.update("UPDATE merchants SET pg_fee_rate=0.01 WHERE merchant_id=?", MERCHANT);
        create(PAYMENT + 2, MERCHANT, 10000);
        pay(MERCHANT);
        assertThat(remaining(CANCELLATION)).isZero();
        assertThat(allocations()).isZero();
        assertThat(currentPayout(MERCHANT).getPayoutAmount()).isEqualTo(9700);
    }

    // 두 키셋 페이지에 걸친 회수도 모든 원장을 한 번씩 차감한다.
    @Test
    void recover_whenAdjustmentsCrossPageBoundary_allocatesEveryDebtOnce() {
        for (int n = 1; n <= 200; n++) create(PAYMENT + n, MERCHANT, 100);
        pay(MERCHANT);
        backdate(currentPayout(MERCHANT).getPayoutId(), 1);
        adjustments.save(SettlementRefundAdjustment.received(
                event(CANCELLATION, source),
                settlements.findByPaymentId(PAYMENT).orElseThrow(),
                Instant.now()));
        for (int n = 1; n <= 200; n++) {
            var c = captures.findByPaymentId(PAYMENT + n).orElseThrow();
            adjustments.save(SettlementRefundAdjustment.received(
                    event(CANCELLATION + n, c),
                    settlements.findByPaymentId(PAYMENT + n).orElseThrow(),
                    Instant.now()));
        }
        create(PAYMENT + 201, MERCHANT, 40000);
        pay(MERCHANT);
        assertThat(currentPayout(MERCHANT).getRefundDeductionTotal()).isEqualTo(29100);
        assertThat(currentPayout(MERCHANT).getPayoutAmount()).isEqualTo(9700);
        assertThat(allocations()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "SELECT SUM(remaining_amount) FROM settlement_refund_adjustments WHERE merchant_id=?",
                        Long.class,
                        MERCHANT))
                .isZero();
    }

    // 배분은 금융 이력이므로 중복·0원·없는 원본 참조·원본 삭제를 DB에서도 거절한다.
    @Test
    void allocation_whenConstraintViolated_rejectsWithoutChangingHistory() {
        var a = refunds.record(event(CANCELLATION, source));
        create(PAYMENT + 1, MERCHANT, 20000);
        pay(MERCHANT);
        long id = currentPayout(MERCHANT).getPayoutId();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO payout_refund_allocations(payout_id,adjustment_id,applied_amount,created_at) SELECT payout_id,adjustment_id,applied_amount,created_at FROM payout_refund_allocations WHERE payout_id=?",
                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE payout_refund_allocations SET applied_amount=0 WHERE payout_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payout_refund_allocations SET payout_id=9223372036854775807 WHERE payout_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM settlement_refund_adjustments WHERE adjustment_id=?", a.getAdjustmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        conserved(CANCELLATION);
    }
}
