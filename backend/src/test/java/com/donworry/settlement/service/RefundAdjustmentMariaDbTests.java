package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=2")
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundAdjustmentMariaDbTests {
    private static final long MERCHANT = 939301, PAYMENT = 9393001, CANCELLATION = 9393101;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    CaptureRepository captures;

    @Autowired
    SettlementRepository settlements;

    @Autowired
    SettlementService creation;

    @Autowired
    SettlementCompletionTransactionService payout;

    @Autowired
    RefundAdjustmentService service;

    @MockitoSpyBean
    SettlementRefundAdjustmentRepository adjustments;

    private Capture source;
    private PaymentRefundSucceeded event;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,'P6-REFUND-TEST','refund test','ACTIVE',0.01,UTC_TIMESTAMP(6))",
                MERCHANT);
        source = create(PAYMENT, 10000L);
        event = new PaymentRefundSucceeded(
                CANCELLATION,
                PAYMENT,
                source.getCaptureId(),
                MERCHANT,
                10000L,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private Capture create(long payment, long amount) {
        var at = Instant.now().minusSeconds(10 * 86400).truncatedTo(ChronoUnit.MICROS);
        var c = Capture.processing(payment, MERCHANT, "P6-REFUND-" + payment, amount, at);
        c.complete(at);
        c = captures.save(c);
        creation.createSettlement(c);
        return c;
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM settlement_refund_adjustments WHERE merchant_id=?", MERCHANT);
        jdbc.update(
                "DELETE i FROM merchant_payout_items i JOIN merchant_payouts p ON p.payout_id=i.payout_id WHERE p.merchant_id=?",
                MERCHANT);
        jdbc.update("DELETE FROM merchant_payouts WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM settlements WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM captures WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", MERCHANT);
    }

    private long count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_refund_adjustments WHERE merchant_id=?", Long.class, MERCHANT);
    }

    private PaymentRefundSucceeded changed(
            Long cancel, Long pay, Long capture, Long merchant, Long amount, Instant at) {
        return new PaymentRefundSucceeded(cancel, pay, capture, merchant, amount, at);
    }

    @Test
    void record_whenUnpaid_preservesSourcesAndStoresOnlyPendingNetAmount() {
        var before = jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", PAYMENT);
        var captured = jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", PAYMENT);
        var a = service.record(event);
        assertThat(a.getRefundAmount()).isEqualTo(10000);
        assertThat(a.getCardFeeReturn()).isEqualTo(200);
        assertThat(a.getPgFeeReturn()).isEqualTo(100);
        assertThat(a.getAdjustmentAmount()).isEqualTo(9700);
        assertThat(a.getRemainingAmount()).isEqualTo(9700);
        assertThat(a.getStatus()).isEqualTo(RefundAdjustmentStatus.PENDING);
        assertThat(a.getDisposition()).isNull();
        assertThat(a.getCanceledAt()).isEqualTo(event.canceledAt());
        assertThat(jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", PAYMENT))
                .isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", PAYMENT))
                .isEqualTo(captured);
    }

    @Test
    void record_whenAlreadyPaid_doesNotChangePayoutOrChooseRecoveryYet() {
        payout.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE);
        var before = jdbc.queryForList("SELECT * FROM merchant_payouts WHERE merchant_id=?", MERCHANT);
        var a = service.record(event);
        assertThat(a.getStatus()).isEqualTo(RefundAdjustmentStatus.PENDING);
        assertThat(a.getDisposition()).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM merchant_payouts WHERE merchant_id=?", MERCHANT))
                .isEqualTo(before);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    void record_whenRetransmitted_preservesSnapshotDespiteNewMerchantRate() {
        var first = service.record(event);
        jdbc.update("UPDATE merchants SET pg_fee_rate=0.03 WHERE merchant_id=?", MERCHANT);
        var second = service.record(event);
        assertThat(second.getAdjustmentId()).isEqualTo(first.getAdjustmentId());
        assertThat(count()).isEqualTo(1);
        assertThat(second.getPgFeeReturn()).isEqualTo(100);
        assertThat(second.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
    }

    @Test
    void record_whenAlreadyApplied_doesNotResetRemainingRecovery() {
        var a = service.record(event);
        jdbc.update(
                "UPDATE settlement_refund_adjustments SET status='APPLIED',disposition='RECOVERY',remaining_amount=4000 WHERE adjustment_id=?",
                a.getAdjustmentId());
        var repeated = service.record(event);
        assertThat(repeated.getStatus()).isEqualTo(RefundAdjustmentStatus.APPLIED);
        assertThat(repeated.getRemainingAmount()).isEqualTo(4000);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void record_whenSameCancellationChangesAnyField_rejects() {
        service.record(event);
        var other = create(PAYMENT + 1, 10000);
        for (var bad : List.of(
                changed(CANCELLATION, PAYMENT, source.getCaptureId(), MERCHANT, 9999L, event.canceledAt()),
                changed(CANCELLATION, PAYMENT, source.getCaptureId(), MERCHANT + 1, 10000L, event.canceledAt()),
                changed(CANCELLATION, PAYMENT, source.getCaptureId() + 10000, MERCHANT, 10000L, event.canceledAt()),
                changed(
                        CANCELLATION,
                        PAYMENT,
                        source.getCaptureId(),
                        MERCHANT,
                        10000L,
                        event.canceledAt().plusSeconds(1)),
                changed(CANCELLATION, PAYMENT + 1, other.getCaptureId(), MERCHANT, 10000L, event.canceledAt()))) {
            assertThatThrownBy(() -> service.record(bad)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void record_whenDifferentCancellationForSamePayment_rejectsSecondRefund() {
        service.record(event);
        assertThatThrownBy(() -> service.record(changed(
                        CANCELLATION + 1, PAYMENT, source.getCaptureId(), MERCHANT, 10000L, event.canceledAt())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void record_whenSourceMissingPartialOrInvalidState_rejectsWithoutReceipt() {
        var missing = changed(CANCELLATION, PAYMENT + 99, source.getCaptureId(), MERCHANT, 10000L, event.canceledAt());
        assertThatThrownBy(() -> service.record(missing)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.record(
                        changed(CANCELLATION, PAYMENT, source.getCaptureId(), MERCHANT, 5000L, event.canceledAt())))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE settlements SET status='CANCELED' WHERE payment_id=?", PAYMENT);
        assertThatThrownBy(() -> service.record(event)).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void record_whenDatabaseInsertFails_rollsBackAndCanRetry() {
        doThrow(new IllegalStateException("simulated adjustment insert failure"))
                .when(adjustments)
                .save(any());
        assertThatThrownBy(() -> service.record(event)).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        reset(adjustments);
        service.record(event);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void record_whenConcurrent_returnsSameReceiptAfterRowLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("source release timed out");
                    return Optional.of(source);
                })
                .when(captures)
                .findById(source.getCaptureId());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.record(event));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var secondStarted = new CountDownLatch(1);
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return service.record(event);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(20, TimeUnit.SECONDS).getAdjustmentId())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).getAdjustmentId());
        } finally {
            release.countDown();
        }
        assertThat(count()).isEqualTo(1);
        verify(captures, times(1)).findById(source.getCaptureId());
    }

    @Test
    void database_whenDuplicateInvalidStateOrDeleteSource_rejects() {
        var a = service.record(event);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE settlement_refund_adjustments SET remaining_amount=adjustment_amount+1 WHERE adjustment_id=?",
                        a.getAdjustmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE settlement_refund_adjustments SET status='APPLIED' WHERE adjustment_id=?",
                        a.getAdjustmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO settlement_refund_adjustments(cancellation_id,payment_id,capture_id,settlement_id,merchant_id,refund_amount,card_fee_return,pg_fee_return,adjustment_amount,remaining_amount,status,canceled_at,created_at,updated_at) SELECT cancellation_id,payment_id,capture_id,settlement_id,merchant_id,refund_amount,card_fee_return,pg_fee_return,adjustment_amount,remaining_amount,status,canceled_at,created_at,updated_at FROM settlement_refund_adjustments WHERE adjustment_id=?",
                        a.getAdjustmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM settlements WHERE payment_id=?", PAYMENT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void record_whenAmountsAreLarge_preservesExactLongValues() {
        var c = create(PAYMENT + 1, Long.MAX_VALUE);
        var a = service.record(
                changed(CANCELLATION + 1, PAYMENT + 1, c.getCaptureId(), MERCHANT, Long.MAX_VALUE, event.canceledAt()));
        var original = settlements.findByPaymentId(PAYMENT + 1).orElseThrow();
        assertThat(a.getAdjustmentAmount()).isEqualTo(original.getSettlementAmount());
        assertThat(a.getRefundAmount()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void record_whenNetZero_recordsZeroAdjustment() {
        jdbc.update("UPDATE merchants SET pg_fee_rate=0.98 WHERE merchant_id=?", MERCHANT);
        var c = create(PAYMENT + 1, 10000);
        var a = service.record(
                changed(CANCELLATION + 1, PAYMENT + 1, c.getCaptureId(), MERCHANT, 10000L, event.canceledAt()));
        assertThat(a.getAdjustmentAmount()).isZero();
        assertThat(a.getRemainingAmount()).isZero();
    }
}
