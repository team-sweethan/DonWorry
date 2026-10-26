package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.api.CaptureReader;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class CaptureReaderMariaDbTests {
    private static final long PAYMENT_ID = 91891L;
    private static final Instant AT = Instant.parse("2026-10-26T01:02:03.123456Z");

    private long eventCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?",
                Long.class,
                PAYMENT_ID);
    }

    @Autowired
    CaptureReader reader;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM settlements WHERE payment_id = ?", PAYMENT_ID);
        jdbc.update("DELETE FROM captures WHERE payment_id = ?", PAYMENT_ID);
    }

    private long seed(String status, boolean settlement) {
        jdbc.update(
                """
                INSERT INTO captures
                (payment_id, merchant_id, order_id, capture_amount, status, captured_at, retry_count, created_at, updated_at)
                VALUES (?, 1, 'READER-189', 10001, ?, ?, 0, ?, ?)
                """,
                PAYMENT_ID,
                status,
                status.equals("COMPLETED") ? Timestamp.from(AT) : null,
                Timestamp.from(AT),
                Timestamp.from(AT));
        long id = jdbc.queryForObject("SELECT capture_id FROM captures WHERE payment_id = ?", Long.class, PAYMENT_ID);
        if (settlement) {
            jdbc.update("""
                    INSERT INTO settlements
                    (capture_id, payment_id, merchant_id, gross_amount, card_fee_rate, pg_fee_rate,
                     card_fee_amount, pg_fee_amount, settlement_amount, status, created_at, updated_at)
                    VALUES (?, ?, 1, 10001, 0.02, 0.01, 200, 100, 9701, 'SCHEDULED', ?, ?)
                    """, id, PAYMENT_ID, Timestamp.from(AT), Timestamp.from(AT));
        }
        return id;
    }

    // 실제 DB의 완료 상태와 마이크로초 정밀도를 유지하고 부작용을 만들지 않는다
    @Test
    void findByPaymentId_whenCompleted_preservesPrecisionAndRows() {
        long id = seed("COMPLETED", true);
        long events = eventCount();
        var result = reader.findByPaymentId(PAYMENT_ID).orElseThrow();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(result.captureId()).isEqualTo(id);
        assertThat(result.captureAmount()).isEqualTo(10001L);
        assertThat(result.captureStatus()).isEqualTo("COMPLETED");
        assertThat(result.capturedAt()).isEqualTo(AT);
        assertThat(result.settlementExists()).isTrue();
        assertThat(result.settlementStatus()).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject(
                                "SELECT updated_at FROM captures WHERE payment_id = ?", Timestamp.class, PAYMENT_ID)
                        .toInstant())
                .isEqualTo(AT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlements WHERE payment_id = ?", Long.class, PAYMENT_ID))
                .isEqualTo(1);
        assertThat(eventCount()).isEqualTo(events);
    }

    // 완료 전 상태와 정산 누락을 그대로 반환한다
    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "FAILED", "COMPLETED"})
    void findByPaymentId_whenSettlementMissing_returnsStoredFacts(String status) {
        seed(status, false);
        var result = reader.findByPaymentId(PAYMENT_ID).orElseThrow();
        assertThat(result.captureStatus()).isEqualTo(status);
        if (!status.equals("COMPLETED")) assertThat(result.capturedAt()).isNull();
        assertThat(result.settlementExists()).isFalse();
        assertThat(result.settlementStatus()).isNull();
    }

    // 매입이 없으면 빈 결과를 반환한다
    @Test
    void findByPaymentId_whenAbsent_returnsEmpty() {
        assertThat(reader.findByPaymentId(PAYMENT_ID)).isEmpty();
    }

    // 호출자 트랜잭션과 분리된 읽기 전용 트랜잭션을 사용한다
    @Test
    void findByPaymentId_whenCallerHasUncommittedChange_readsCommittedFacts() {
        seed("COMPLETED", true);
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE captures SET capture_amount = 20000 WHERE payment_id = ?", PAYMENT_ID);
            assertThat(reader.findByPaymentId(PAYMENT_ID).orElseThrow().captureAmount())
                    .isEqualTo(10001L);
            status.setRollbackOnly();
        });
    }
}
