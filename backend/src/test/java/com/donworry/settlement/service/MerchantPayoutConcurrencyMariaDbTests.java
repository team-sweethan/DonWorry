package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("mariadb")
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=2")
@Import(MariaDbTestContainerConfig.class)
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

    @MockitoSpyBean
    SettlementRepository settlements;

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
        jdbc.update(
                "DELETE i FROM merchant_payout_items i JOIN merchant_payouts p ON p.payout_id=i.payout_id WHERE p.merchant_id=?",
                MERCHANT);
        jdbc.update("DELETE FROM merchant_payouts WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM settlements WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM captures WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", MERCHANT);
    }
    // 두 물리 연결이 모두 '지급 전' 확인을 마친 뒤 실제 UPSERT/행 잠금에서 경쟁한다.
    @Test
    void complete_whenTwoConnectionsRace_recordsOnePayoutAndEachItemOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Long>newKeySet();
        doAnswer(call -> {
                    connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    barrier.await(10, TimeUnit.SECONDS);
                    // 두 대상이 존재하는 fixture의 사전 확인만 동기화한다. 지급/상세/정산 SQL은 실제로 실행한다.
                    return 2L;
                })
                .when(settlements)
                .countForPayout(eq(MERCHANT), any(), anyLong());
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
}
