package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.settlement.entity.Capture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 자동 검사에서 개발 DB에 쓰지 않도록 명시적인 환경 변수가 있을 때만 실행한다.
@Tag("local-db")
@EnabledIfEnvironmentVariable(named = "SETTLEMENT_LOCAL_DEMO", matches = "true")
@SpringBootTest
class LocalSettlementDemoTests {
    @Autowired
    CaptureService captureService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // P5와 외부 연동 없이 서비스가 커밋한 샘플을 로컬 DB에 남겨 직접 조회할 수 있게 한다
    @Test
    void processCapture_whenLocalSample_persistsCaptureAndScheduledSettlement() {
        long paymentId = System.currentTimeMillis();
        String orderId = "LOCAL-SETTLEMENT-" + paymentId;
        Capture capture = captureService.processCapture(paymentId, 1L, orderId, 10001L);

        var row = jdbcTemplate.queryForMap("""
                SELECT c.capture_id, c.status AS capture_status,
                       s.status AS settlement_status, s.gross_amount,
                       s.card_fee_amount, s.pg_fee_amount, s.settlement_amount
                FROM captures c
                JOIN settlements s ON s.capture_id = c.capture_id
                WHERE c.payment_id = ?
                """, paymentId);

        assertThat(row.get("capture_id")).isEqualTo(capture.getCaptureId());
        assertThat(row.get("capture_status")).isEqualTo("COMPLETED");
        assertThat(row.get("settlement_status")).isEqualTo("SCHEDULED");
        assertThat(((Number) row.get("gross_amount")).longValue()).isEqualTo(10001L);
        assertThat(((Number) row.get("card_fee_amount")).longValue()).isEqualTo(200L);
        assertThat(((Number) row.get("pg_fee_amount")).longValue()).isEqualTo(100L);
        assertThat(((Number) row.get("settlement_amount")).longValue()).isEqualTo(9701L);

        System.out.printf(
                "LOCAL SETTLEMENT SAVED: paymentId=%d, orderId=%s, captureId=%d, net=9701%n",
                paymentId, orderId, capture.getCaptureId());
    }
}
