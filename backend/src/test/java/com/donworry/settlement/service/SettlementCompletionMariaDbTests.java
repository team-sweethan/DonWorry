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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    PlatformTransactionManager transactionManager;

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
        jdbc.update("DELETE FROM settlements WHERE merchant_id IN (?, ?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM captures WHERE merchant_id IN (?, ?)", MERCHANT, MERCHANT + 1);
        jdbc.update(
                "DELETE i FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.order_id LIKE 'P6-BATCH-%'");
        jdbc.update("DELETE FROM order_fulfillments WHERE order_id LIKE 'P6-BATCH-%'");
        jdbc.update("DELETE FROM merchants WHERE merchant_id IN (?, ?)", MERCHANT, MERCHANT + 1);
    }

    private void seed(long payment, Instant pickup) {
        var order = "P6-BATCH-" + payment;
        captures.processCapture(payment, MERCHANT, order, 10000L);
        if (pickup != null) {
            pickups.recordSchedule(
                    MERCHANT,
                    order,
                    pickup.minusSeconds(3600),
                    java.util.List.of(new PickupOrderItemRequest("케이크", 1)));
            pickups.recordCompletion(MERCHANT, order, pickup);
        }
    }
    // 예정만 있는 건과 아직 72시간 전인 건은 남기고, 누락된 과거 대상은 한 번만 완료한다
    @Test
    void run_whenOverdue_completesOnceAndPreservesAmounts() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        seed(PAYMENT, now.minusSeconds(73 * 3600));
        seed(PAYMENT + 1, now.minusSeconds(71 * 3600));
        seed(PAYMENT + 2, null);
        var before = settlements.findByPaymentId(PAYMENT).orElseThrow();
        batch.run(now);
        var first = settlements.findByPaymentId(PAYMENT).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(first.getCompletedAt()).isAfterOrEqualTo(now);
        assertThat(first.getSettlementAmount()).isEqualTo(before.getSettlementAmount());
        assertThat(first.getScheduledAt()).isEqualTo(before.getScheduledAt());
        batch.run(Instant.now());
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getCompletedAt())
                .isEqualTo(first.getCompletedAt());
        assertThat(settlements.findByPaymentId(PAYMENT + 1).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(settlements.findByPaymentId(PAYMENT + 2).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
    }

    @Test
    void run_whenMerchantsShareOrderNumber_usesEachMerchantsPickup() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var order = "P6-BATCH-SHARED";
        var overdue = now.minusSeconds(73 * 3600);
        var notDue = now.minusSeconds(71 * 3600);
        captures.processCapture(PAYMENT, MERCHANT, order, 10000L);
        captures.processCapture(PAYMENT + 1, MERCHANT + 1, order, 20000L);
        pickups.recordCompletion(MERCHANT, order, overdue);
        pickups.recordCompletion(MERCHANT + 1, order, notDue);
        var id = settlements.findByPaymentId(PAYMENT).orElseThrow().getSettlementId();

        assertThat(completion.complete(id, MERCHANT + 1, order, now)).isEqualTo("MERCHANT_MISMATCH");
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);

        batch.run(now);

        var first = settlements.findByPaymentId(PAYMENT).orElseThrow();
        var second = settlements.findByPaymentId(PAYMENT + 1).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(first.getScheduledAt()).isEqualTo(overdue.plusSeconds(72 * 3600));
        assertThat(second.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(second.getScheduledAt()).isEqualTo(notDue.plusSeconds(72 * 3600));
    }

    // 두 실행이 같은 대상을 읽어도 행 잠금 후 재판정으로 완료는 한 번만 반영한다
    @Test
    void complete_whenConcurrent_commitsOnce() throws Exception {
        var at = Instant.now().minusSeconds(73 * 3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        seed(PAYMENT, at);
        var id = settlements.findByPaymentId(PAYMENT).orElseThrow().getSettlementId();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return completion.complete(id, MERCHANT, "P6-BATCH-" + PAYMENT, Instant.now());
            };
            var a = pool.submit(task);
            var b = pool.submit(task);
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMPLETED", "NOT_SCHEDULED");
        }
    }
    // 저장 후 롤백되면 예정 상태를 유지하고 다음 실행에서 복구한다
    @Test
    void complete_whenRolledBack_nextRunRecovers() {
        var at = Instant.now().minusSeconds(73 * 3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        seed(PAYMENT, at);
        var id = settlements.findByPaymentId(PAYMENT).orElseThrow().getSettlementId();
        var tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    var s = settlements.findForCompletion(id).orElseThrow();
                    s.complete(Instant.now());
                    settlements.flush();
                    throw new IllegalStateException("simulated storage rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.SCHEDULED);
        completion.complete(id, MERCHANT, "P6-BATCH-" + PAYMENT, Instant.now());
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
    }
}
