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
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=2")
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MerchantPayoutConcurrencyMariaDbTests {
    private static final long MERCHANT = 933701;

    @Autowired
    CaptureRepository captures;

    @Autowired
    SettlementService creation;

    @Autowired
    SettlementCompletionTransactionService completion;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RefundAdjustmentService refunds;

    @MockitoSpyBean
    SettlementRepository settlements;

    @MockitoSpyBean
    MerchantSettlementLockRepository merchantLockRows;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,'P6-PAYOUT-RACE','payout race','ACTIVE',0.01,UTC_TIMESTAMP(6))",
                MERCHANT);
        var at = Instant.now().minusSeconds(10 * 86400);
        for (long payment : new long[] {9337001, 9337002}) {
            var c = Capture.processing(payment, MERCHANT, "P6-PAYOUT-RACE-" + payment, 10000L, at);
            c.complete(at);
            creation.createSettlement(captures.save(c));
        }
    }

    @AfterEach
    void clean() {
        for (long merchant : new long[] {MERCHANT, MERCHANT + 1}) {
            jdbc.update("DELETE FROM settlement_refund_adjustments WHERE merchant_id=?", merchant);
            jdbc.update(
                    "DELETE i FROM merchant_payout_items i JOIN merchant_payouts p ON p.payout_id=i.payout_id WHERE p.merchant_id=?",
                    merchant);
            jdbc.update("DELETE FROM merchant_payouts WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM settlements WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM captures WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM merchant_settlement_locks WHERE merchant_id=?", merchant);
            jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchant);
        }
    }
    // 두 물리 연결이 공통 가맹점 잠금 직전에 만나 실제 행 잠금에서 경쟁한다.
    @Test
    void complete_whenTwoConnectionsRace_recordsOnePayoutAndEachItemOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Long>newKeySet();
        doAnswer(call -> {
                    connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    barrier.await(10, TimeUnit.SECONDS);
                    // 잠금 직전만 동기화한다. 공통 잠금·지급·상세·정산 SQL은 실제로 실행한다.
                    return real(merchantLockRows, call);
                })
                .when(merchantLockRows)
                .ensurePresent(eq(MERCHANT), any());
        var cutoff = Instant.now();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> completion.completeMerchant(MERCHANT, cutoff, Long.MAX_VALUE);
            var first = pool.submit(task);
            var second = pool.submit(task);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMPLETED", "ALREADY_COMPLETED");
        }
        assertThat(connections).hasSize(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT payout_amount FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(19400);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchant_payout_items i JOIN merchant_payouts p ON p.payout_id=i.payout_id WHERE p.merchant_id=?",
                        Long.class,
                        MERCHANT))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlements WHERE merchant_id=? AND status='COMPLETED'",
                        Long.class,
                        MERCHANT))
                .isEqualTo(2);
    }

    // Spring Data 저장소 프록시는 추상 메서드이므로 원래 프록시 위임으로 실제 SQL을 실행한다.
    private Object real(Object repository, org.mockito.invocation.InvocationOnMock call) throws Throwable {
        return mockingDetails(repository)
                .getMockCreationSettings()
                .getDefaultAnswer()
                .answer(call);
    }

    private PaymentRefundSucceeded refund() {
        var c = captures.findByPaymentId(9337001L).orElseThrow();
        return new PaymentRefundSucceeded(
                9337101L,
                9337001L,
                c.getCaptureId(),
                MERCHANT,
                10000L,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private void awaitRelease(CountDownLatch entered, CountDownLatch release) throws Exception {
        entered.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("race release timed out");
    }
    // 환불 수신이 먼저 가맹점 잠금을 잡으면 같은 지급에서 해당 건을 제외한다.
    @Test
    void race_whenRefundLocksFirst_excludesExactlyOnce() throws Exception {
        race(false, false);
    }
    // 지급이 먼저 잠금을 잡으면 완료 원장을 보존하고 환불을 후속 회수 대상으로 남긴다.
    @Test
    void race_whenPayoutLocksFirst_preservesPayoutAndLeavesRecoveryPending() throws Exception {
        race(true, false);
    }
    // 먼저 잠근 지급이 실패하면 롤백 뒤 기다리던 환불이 미지급 건을 제외할 수 있다.
    @Test
    void race_whenPayoutRollsBack_waitingRefundExcludesBeforeRetry() throws Exception {
        race(true, true);
    }

    private void race(boolean payoutFirst, boolean rollback) throws Exception {
        var event = refund();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var connections = ConcurrentHashMap.<Long>newKeySet();
        doAnswer(call -> {
                    connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    return real(merchantLockRows, call);
                })
                .when(merchantLockRows)
                .ensurePresent(eq(MERCHANT), any());
        if (payoutFirst) {
            doAnswer(call -> {
                        var targets = real(settlements, call);
                        awaitRelease(entered, release);
                        if (rollback) throw new IllegalStateException("simulated payout failure");
                        return targets;
                    })
                    .when(settlements)
                    .findPayoutTargetIds(eq(MERCHANT), any(), anyLong());
        } else {
            doAnswer(call -> {
                        var target = real(settlements, call);
                        awaitRelease(entered, release);
                        return target;
                    })
                    .when(settlements)
                    .findForRefund(event.paymentId());
        }
        var cutoff = Instant.now();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> payoutFirst
                    ? completion.completeMerchant(MERCHANT, cutoff, Long.MAX_VALUE)
                    : refunds.record(event));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var secondEntered = new CountDownLatch(1);
            Future<?> second = pool.submit(() -> {
                secondEntered.countDown();
                return payoutFirst
                        ? refunds.record(event)
                        : completion.completeMerchant(MERCHANT, cutoff, Long.MAX_VALUE);
            });
            assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            if (rollback)
                assertThatThrownBy(() -> first.get(20, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            else first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(connections).hasSize(2);
        reset(settlements, merchantLockRows);
        if (rollback)
            assertThat(completion.completeMerchant(MERCHANT, Instant.now(), Long.MAX_VALUE))
                    .isEqualTo("COMPLETED");
        long expected = payoutFirst && !rollback ? 19400 : 9700;
        assertThat(jdbc.queryForObject(
                        "SELECT payout_amount FROM merchant_payouts WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM settlement_refund_adjustments WHERE cancellation_id=?",
                        String.class,
                        event.cancellationId()))
                .isEqualTo(payoutFirst && !rollback ? "PENDING" : "APPLIED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM settlements WHERE payment_id=?", String.class, event.paymentId()))
                .isEqualTo(payoutFirst && !rollback ? "COMPLETED" : "CANCELED");
        refunds.record(event);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlement_refund_adjustments WHERE cancellation_id=?",
                        Long.class,
                        event.cancellationId()))
                .isEqualTo(1);
    }

    // 가맹점별 잠금이므로 다른 가맹점의 지급까지 대기시키지 않는다.
    @Test
    void race_whenAnotherMerchantPays_doesNotWaitForRefundLock() throws Exception {
        jdbc.update(
                "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,'P6-PAYOUT-OTHER','other','ACTIVE',0.01,UTC_TIMESTAMP(6))",
                MERCHANT + 1);
        var at = Instant.now().minusSeconds(864000);
        var other = Capture.processing(9338001L, MERCHANT + 1, "OTHER-ORDER", 10000L, at);
        other.complete(at);
        creation.createSettlement(captures.save(other));
        var event = refund();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    var target = real(settlements, call);
                    awaitRelease(entered, release);
                    return target;
                })
                .when(settlements)
                .findForRefund(event.paymentId());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> refunds.record(event));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                var second =
                        pool.submit(() -> completion.completeMerchant(MERCHANT + 1, Instant.now(), Long.MAX_VALUE));
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("COMPLETED");
                assertThat(first.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            first.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }
}
