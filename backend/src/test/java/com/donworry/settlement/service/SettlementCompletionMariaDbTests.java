package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.dto.PickupOrderItemRequest;
import com.donworry.settlement.entity.SettlementStatus;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("mariadb")
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
@Import(MariaDbTestContainerConfig.class)
class SettlementCompletionMariaDbTests {
    private static final long PAYMENT = 93201;
    private static final long MERCHANT = 93201;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    CaptureService captures;

    @Autowired
    OrderFulfillmentService pickups;

    @Autowired
    SettlementRepository settlements;

    @Autowired
    SettlementCompletionBatchService batch;

    @Autowired
    SettlementCompletionTransactionService completion;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MerchantPayoutRepository payouts;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    MerchantPayoutItemRepository payoutItems;

    private void assertNoPayout(long merchant) {
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payouts WHERE merchant_id=?", Long.class, merchant))
                .isZero();
    }

    private long upper() {
        return jdbc.queryForObject("SELECT MAX(settlement_id) FROM settlements", Long.class);
    }

    @Autowired
    CaptureSettlementSchedule schedule;

    private void capturedAt(long payment, Instant at) {
        jdbc.update(
                "UPDATE captures SET captured_at=? WHERE payment_id=?",
                com.donworry.settlement.UtcJdbcTimestamp.utcTimestamp(at),
                payment);
        var due = com.donworry.settlement.UtcJdbcTimestamp.utcTimestamp(schedule.settlementAt(at));
        jdbc.update(
                "UPDATE settlements SET scheduled_at=?,expected_settlement_at=? WHERE payment_id=?", due, due, payment);
    }

    @org.junit.jupiter.api.BeforeEach
    void createMerchantFeeFixture() {
        for (long id : new long[] {MERCHANT, MERCHANT + 1}) {
            jdbc.update(
                    "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,?,'ACTIVE',0.01,UTC_TIMESTAMP(6))",
                    id,
                    "P6-FEE-FIXTURE-" + id,
                    "P6 test");
        }
    }

    @AfterEach
    void clean() {
        jdbc.update(
                "DELETE i FROM merchant_payout_items i JOIN merchant_payouts p ON p.payout_id=i.payout_id WHERE p.merchant_id IN (?,?)",
                MERCHANT,
                MERCHANT + 1);
        jdbc.update("DELETE FROM merchant_payouts WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM settlements WHERE merchant_id IN (?, ?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM captures WHERE merchant_id IN (?, ?)", MERCHANT, MERCHANT + 1);
        jdbc.update(
                "DELETE i FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.order_id LIKE 'P6-BATCH-%'");
        jdbc.update("DELETE FROM order_fulfillments WHERE order_id LIKE 'P6-BATCH-%'");
        jdbc.update("DELETE FROM merchants WHERE merchant_id IN (?, ?)", MERCHANT, MERCHANT + 1);
    }

    private void seed(long payment, Instant pickup) {
        if (pickup != null) pickup = pickup.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var order = "P6-BATCH-" + payment;
        captures.processCapture(payment, MERCHANT, order, 10000L);
        capturedAt(payment, pickup == null ? Instant.now().minusSeconds(10 * 86400) : pickup);
        if (pickup != null) {
            pickups.recordSchedule(
                    MERCHANT,
                    order,
                    pickup.minusSeconds(3600),
                    java.util.List.of(new PickupOrderItemRequest("케이크", 1)));
            pickups.recordCompletion(MERCHANT, order, pickup);
        }
    }
    // 픽업이 없어도 기한이 지난 매입은 완료하며 미래 정산일은 유지한다
    @Test
    void run_whenOverdue_completesOnceAndPreservesAmounts() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        seed(PAYMENT, now.minusSeconds(10 * 86400));
        seed(PAYMENT + 1, now);
        seed(PAYMENT + 2, null);
        var before = settlements.findByPaymentId(PAYMENT).orElseThrow();
        batch.run(now);
        var first = settlements.findByPaymentId(PAYMENT).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(first.getCompletedAt()).isAfterOrEqualTo(now);
        assertThat(first.getSettlementAmount()).isEqualTo(before.getSettlementAmount());
        assertThat(first.getScheduledAt()).isEqualTo(before.getScheduledAt());
        var payout = payouts.findByMerchantIdAndPayoutDate(MERCHANT, LocalDate.now(SettlementReportService.ZONE))
                .orElseThrow();
        assertThat(payout.getSettlementTotal()).isEqualTo(19400L);
        assertThat(payout.getPayoutAmount()).isEqualTo(19400L);
        assertThat(payout.getRefundDeductionTotal()).isZero();
        assertThat(payout.getCompletedAt()).isEqualTo(first.getCompletedAt());
        assertThat(payoutItems.findByPayoutIdOrderBySettlementId(payout.getPayoutId()))
                .hasSize(2);
        batch.run(Instant.now());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(1);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getCompletedAt())
                .isEqualTo(first.getCompletedAt());
        assertThat(settlements.findByPaymentId(PAYMENT + 1).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(settlements.findByPaymentId(PAYMENT + 2).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    void run_whenMerchantsShareOrderNumber_usesEachMerchantsCaptureDate() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var order = "P6-BATCH-SHARED";
        var overdue = now.minusSeconds(10 * 86400);
        var notDue = now;
        captures.processCapture(PAYMENT, MERCHANT, order, 10000L);
        captures.processCapture(PAYMENT + 1, MERCHANT + 1, order, 20000L);
        capturedAt(PAYMENT, overdue);
        capturedAt(PAYMENT + 1, notDue);
        pickups.recordCompletion(MERCHANT, order, overdue);
        pickups.recordCompletion(MERCHANT + 1, order, notDue);
        var id = settlements.findByPaymentId(PAYMENT).orElseThrow().getSettlementId();

        assertThat(completion.completeMerchant(MERCHANT + 1, now, Long.MAX_VALUE))
                .isEqualTo("EMPTY");
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);

        batch.run(now);

        var first = settlements.findByPaymentId(PAYMENT).orElseThrow();
        var second = settlements.findByPaymentId(PAYMENT + 1).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(first.getScheduledAt()).isEqualTo(schedule.settlementAt(overdue));
        assertThat(second.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(second.getScheduledAt()).isEqualTo(schedule.settlementAt(notDue));
    }

    // 두 실행이 같은 대상을 읽어도 행 잠금 후 재판정으로 완료는 한 번만 반영한다
    @Test
    void complete_whenConcurrent_commitsOnce() throws Exception {
        var at = Instant.now().minusSeconds(10 * 86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        seed(PAYMENT, at);
        var id = settlements.findByPaymentId(PAYMENT).orElseThrow().getSettlementId();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return completion.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE);
            };
            var a = pool.submit(task);
            var b = pool.submit(task);
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMPLETED", "ALREADY_COMPLETED");
        }
    }
    // 저장 후 롤백되면 예정 상태를 유지하고 다음 실행에서 복구한다
    @Test
    void complete_whenRolledBack_nextRunRecovers() {
        var at = Instant.now().minusSeconds(10 * 86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        seed(PAYMENT, at);
        var id = settlements.findByPaymentId(PAYMENT).orElseThrow().getSettlementId();
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated detail storage failure"))
                .when(payoutItems)
                .saveAll(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> completion.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE))
                .isInstanceOf(IllegalStateException.class);
        assertNoPayout(MERCHANT);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payout_items i JOIN settlements s ON s.settlement_id=i.settlement_id WHERE s.merchant_id=?",
                        Long.class,
                        MERCHANT))
                .isZero();
        org.mockito.Mockito.reset(payoutItems);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        completion.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    void complete_whenLateTargetArrives_defersUntilNextPayoutDate() {
        var past = Instant.now().minusSeconds(10 * 86400);
        seed(PAYMENT, past);
        completion.completeMerchant(MERCHANT, Instant.now(), upper());
        var original = payouts.findByMerchantIdAndPayoutDate(MERCHANT, LocalDate.now(SettlementReportService.ZONE))
                .orElseThrow();
        seed(PAYMENT + 1, past);
        assertThat(completion.completeMerchant(MERCHANT, Instant.now(), upper()))
                .isEqualTo("ALREADY_COMPLETED");
        assertThat(settlements.findByPaymentId(PAYMENT + 1).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(payouts.findById(original.getPayoutId()).orElseThrow().getSettlementTotal())
                .isEqualTo(9700L);
        // 전일 지급 이력 fixture로 다음 날의 재실행을 재현한다. 운영 원장을 수정하는 기능은 없다.
        jdbc.update(
                "UPDATE merchant_payouts SET payout_date=payout_date-INTERVAL 1 DAY,completed_at=completed_at-INTERVAL 1 DAY WHERE payout_id=?",
                original.getPayoutId());
        jdbc.update("UPDATE settlements SET completed_at=completed_at-INTERVAL 1 DAY WHERE payment_id=?", PAYMENT);
        assertThat(completion.completeMerchant(MERCHANT, Instant.now(), upper()))
                .isEqualTo("COMPLETED");
        assertThat(settlements.findByPaymentId(PAYMENT + 1).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(2);
        var today = payouts.findByMerchantIdAndPayoutDate(MERCHANT, LocalDate.now(SettlementReportService.ZONE))
                .orElseThrow();
        assertThat(today.getPayoutAmount()).isEqualTo(9700L);
        assertThat(payoutItems.findByPayoutIdOrderBySettlementId(today.getPayoutId()))
                .hasSize(1);
    }

    @Test
    void run_whenOneMerchantsCaptureInvalid_rollsBackThatMerchantOnly() {
        var past = Instant.now().minusSeconds(10 * 86400);
        seed(PAYMENT, past);
        seed(PAYMENT + 1, past);
        captures.processCapture(PAYMENT + 2, MERCHANT + 1, "P6-BATCH-OTHER", 20000L);
        capturedAt(PAYMENT + 2, past);
        jdbc.update("UPDATE captures SET capture_amount=10001 WHERE payment_id=?", PAYMENT + 1);
        var result = batch.run(Instant.now());
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertNoPayout(MERCHANT);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(settlements.findByPaymentId(PAYMENT + 1).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(settlements.findByPaymentId(PAYMENT + 2).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    void complete_whenSnapshotUpperBoundExcludesNewTarget_keepsItScheduled() {
        var past = Instant.now().minusSeconds(10 * 86400);
        seed(PAYMENT, past);
        var bound = upper();
        seed(PAYMENT + 1, past);
        completion.completeMerchant(MERCHANT, Instant.now(), bound);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
        assertThat(settlements.findByPaymentId(PAYMENT + 1).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
    }

    @Test
    void complete_whenSumOverflows_rollsBackWholeMerchant() {
        var past = Instant.now().minusSeconds(10 * 86400);
        seed(PAYMENT, past);
        seed(PAYMENT + 1, past);
        jdbc.update(
                "UPDATE settlements SET settlement_amount=9223372036854775807 WHERE payment_id IN (?,?)",
                PAYMENT,
                PAYMENT + 1);
        assertThatThrownBy(() -> completion.completeMerchant(MERCHANT, Instant.now(), upper()))
                .isInstanceOf(ArithmeticException.class);
        assertNoPayout(MERCHANT);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
    }

    @Test
    void complete_whenNoEligibleTargets_doesNotLeaveEmptyLedger() {
        seed(PAYMENT, Instant.now());
        assertThat(completion.completeMerchant(MERCHANT, Instant.now(), upper()))
                .isEqualTo("EMPTY");
        assertNoPayout(MERCHANT);
    }

    @Test
    void complete_whenNetIsZero_recordsZeroPayoutWithItsItem() {
        seed(PAYMENT, Instant.now().minusSeconds(10 * 86400));
        jdbc.update("UPDATE settlements SET settlement_amount=0 WHERE payment_id=?", PAYMENT);
        completion.completeMerchant(MERCHANT, Instant.now(), upper());
        var payout = payouts.findByMerchantIdAndPayoutDate(MERCHANT, LocalDate.now(SettlementReportService.ZONE))
                .orElseThrow();
        assertThat(payout.getPayoutAmount()).isZero();
        assertThat(payoutItems.findByPayoutIdOrderBySettlementId(payout.getPayoutId()))
                .hasSize(1);
    }
}
