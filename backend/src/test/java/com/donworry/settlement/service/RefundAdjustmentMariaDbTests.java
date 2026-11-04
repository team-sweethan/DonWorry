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
        jdbc.update("DELETE FROM merchant_settlement_locks WHERE merchant_id=?", MERCHANT);
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
    void record_whenUnpaid_excludesAndPreservesFinancialAmounts() {
        var before = jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", PAYMENT);
        var captured = jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", PAYMENT);
        var a = service.record(event);
        assertThat(a.getRefundAmount()).isEqualTo(10000);
        assertThat(a.getCardFeeReturn()).isEqualTo(200);
        assertThat(a.getPgFeeReturn()).isEqualTo(100);
        assertThat(a.getAdjustmentAmount()).isEqualTo(9700);
        assertThat(a.getRemainingAmount()).isZero();
        assertThat(a.getStatus()).isEqualTo(RefundAdjustmentStatus.APPLIED);
        assertThat(a.getDisposition()).isEqualTo(RefundAdjustmentDisposition.UNPAID_EXCLUSION);
        assertThat(a.getCanceledAt()).isEqualTo(event.canceledAt());
        var after = jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", PAYMENT);
        assertThat(after.get("status")).isEqualTo("CANCELED");
        before.remove("status");
        before.remove("updated_at");
        after.remove("status");
        after.remove("updated_at");
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", PAYMENT))
                .isEqualTo(captured);
    }

    @Test
    void record_whenAlreadyPaid_registersRecoveryWithoutChangingPayout() {
        payout.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE);
        var before = jdbc.queryForList("SELECT * FROM merchant_payouts WHERE merchant_id=?", MERCHANT);
        var a = service.record(event);
        assertThat(a.getStatus()).isEqualTo(RefundAdjustmentStatus.APPLIED);
        assertThat(a.getDisposition()).isEqualTo(RefundAdjustmentDisposition.RECOVERY);
        assertThat(a.getRemainingAmount()).isEqualTo(9700);
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
        payout.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE);
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
                        "UPDATE settlement_refund_adjustments SET status='APPLIED',disposition=NULL WHERE adjustment_id=?",
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

    // 환불을 제외한 나머지 순정산액만 실제 지급 원장에 포함한다.
    @Test
    void complete_whenUnpaidRefundExists_paysOnlyOtherSettlement() {
        create(PAYMENT + 1, 10000);
        service.record(event);
        assertThat(payout.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                        "SELECT payout_amount FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(9700);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payout_items i JOIN settlements s ON s.settlement_id=i.settlement_id WHERE s.payment_id=?",
                        Long.class,
                        PAYMENT))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE payment_id=?", String.class, PAYMENT))
                .isEqualTo("CANCELED");
    }

    // 이전 수신 버전의 PENDING은 대상 선정 전에 적용하고 빈 지급 원장을 남기지 않는다.
    @Test
    void complete_whenLegacyPendingIsOnlyTarget_excludesBeforeSelection() {
        var original = settlements.findByPaymentId(PAYMENT).orElseThrow();
        adjustments.save(SettlementRefundAdjustment.received(event, original, Instant.now()));
        assertThat(payout.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE))
                .isEqualTo("EMPTY");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE payment_id=?", String.class, PAYMENT))
                .isEqualTo("CANCELED");
        var a = adjustments.findByCancellationId(CANCELLATION).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(RefundAdjustmentStatus.APPLIED);
        assertThat(a.getDisposition()).isEqualTo(RefundAdjustmentDisposition.UNPAID_EXCLUSION);
        assertThat(a.getRemainingAmount()).isZero();
        service.record(event);
        assertThat(count()).isEqualTo(1);
    }

    // 상태 업데이트의 실제 DB 오류도 수신 INSERT와 정산 변경을 함께 되돌려야 한다.
    @Test
    void record_whenExclusionUpdateFails_rollsBackReceiptAndSettlement() {
        jdbc.execute(
                "CREATE TRIGGER p6_refund_exclusion_failure BEFORE UPDATE ON settlement_refund_adjustments FOR EACH ROW BEGIN IF NEW.merchant_id="
                        + MERCHANT
                        + " AND NEW.status='APPLIED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='simulated exclusion failure'; END IF; END");
        try {
            assertThatThrownBy(() -> service.record(event)).isInstanceOf(RuntimeException.class);
            assertThat(count()).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE payment_id=?", String.class, PAYMENT))
                    .isEqualTo("SCHEDULED");
        } finally {
            jdbc.execute("DROP TRIGGER p6_refund_exclusion_failure");
        }
        assertThat(service.record(event).getDisposition()).isEqualTo(RefundAdjustmentDisposition.UNPAID_EXCLUSION);
    }

    // 지급 상세가 존재하면 SCHEDULED 표시가 잘못돼 있어도 미지급 제외로 덮지 않는다.
    @Test
    void record_whenPayoutItemAlreadyExists_rejectsInconsistentUnpaidState() {
        payout.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE);
        jdbc.update("UPDATE settlements SET status='SCHEDULED',completed_at=NULL WHERE payment_id=?", PAYMENT);
        assertThatThrownBy(() -> service.record(event)).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT payout_amount FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(9700);
    }

    // 키셋 페이지를 넘겨도 제외하면서 건너뛰거나 무한 반복하지 않는다.
    @Test
    void complete_whenLegacyPendingCrossesPageBoundary_excludesEveryRefund() {
        adjustments.save(SettlementRefundAdjustment.received(
                event, settlements.findByPaymentId(PAYMENT).orElseThrow(), Instant.now()));
        for (int n = 1; n <= 200; n++) {
            var c = create(PAYMENT + n, 10000);
            var e = changed(CANCELLATION + n, PAYMENT + n, c.getCaptureId(), MERCHANT, 10000L, event.canceledAt());
            adjustments.save(SettlementRefundAdjustment.received(
                    e, settlements.findByPaymentId(PAYMENT + n).orElseThrow(), Instant.now()));
        }
        assertThat(payout.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE))
                .isEqualTo("EMPTY");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlement_refund_adjustments WHERE merchant_id=? AND status='APPLIED' AND disposition='UNPAID_EXCLUSION' AND remaining_amount=0",
                        Long.class,
                        MERCHANT))
                .isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlements WHERE merchant_id=? AND status='SCHEDULED'",
                        Long.class,
                        MERCHANT))
                .isZero();
    }

    // 준비 행은 금융 실패와 무관한 구조 정보이며 UTC 시각으로 한 번만 저장한다.
    @Test
    void record_whenGatePrepared_keepsOneRowWithUtcCreatedAt() {
        var from = Instant.now().minusSeconds(1);
        service.record(event);
        service.record(event);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_settlement_locks WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(1);
        var at = jdbc.queryForObject(
                        "SELECT created_at FROM merchant_settlement_locks WHERE merchant_id=?",
                        java.time.LocalDateTime.class,
                        MERCHANT)
                .toInstant(java.time.ZoneOffset.UTC);
        assertThat(at).isBetween(from, Instant.now().plusSeconds(1));
    }
    // 회수 시나리오도 같은 Spring 컨텍스트를 공유해 전체 CI의 메모리 사용을 제한한다.
    @Nested
    class Recovery extends RefundRecoveryMariaDbTests {}
}
