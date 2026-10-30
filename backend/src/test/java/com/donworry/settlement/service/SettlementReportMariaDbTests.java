package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.dto.PickupOrderItemRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class SettlementReportMariaDbTests {
    private static final long MERCHANT = 992201L;
    private static final long PAYMENT = 9922001L;
    private static final Instant START = Instant.parse("2026-11-01T15:00:00Z");

    @MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    CaptureService captures;

    @Autowired
    OrderFulfillmentService pickups;

    @Autowired
    SettlementReportService reports;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seedMerchants() {
        for (long id : new long[] {MERCHANT, MERCHANT + 1}) {
            jdbc.update(
                    "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,?,'ACTIVE',0.01,UTC_TIMESTAMP(6))",
                    id,
                    "P6-REPORT-" + id,
                    "report test");
        }
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM settlements WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM captures WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update(
                "DELETE i FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.merchant_id IN (?,?)",
                MERCHANT,
                MERCHANT + 1);
        jdbc.update("DELETE FROM order_fulfillments WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM merchants WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
    }

    // 같은 주문번호도 가맹점별 상품·금액을 분리하고 한국 날짜의 시작 경계를 포함한다.
    @Test
    void report_whenOrderShared_returnsOwnOrderedItemsAndKoreanDay() {
        captures.processCapture(PAYMENT, MERCHANT, "P6-REPORT-SHARED", 10000L);
        captures.processCapture(PAYMENT + 1, MERCHANT + 1, "P6-REPORT-SHARED", 20000L);
        pickups.recordSchedule(
                MERCHANT,
                "P6-REPORT-SHARED",
                START.minusSeconds(72 * 3600),
                List.of(new PickupOrderItemRequest("딸기", 2), new PickupOrderItemRequest("딸기", 1)));
        pickups.recordSchedule(
                MERCHANT + 1,
                "P6-REPORT-SHARED",
                START.minusSeconds(72 * 3600),
                List.of(new PickupOrderItemRequest("다른 가맹점 상품", 3)));
        var result = reports.report(MERCHANT, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2));
        assertThat(result.expectedAmount()).isEqualTo(9700);
        assertThat(result.entries()).hasSize(1);
        var entry = result.entries().getFirst();
        assertThat(entry.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(entry.items()).extracting(i -> i.productName()).containsExactly("딸기", "딸기");
        assertThat(entry.items()).extracting(i -> i.quantity()).containsExactly(2, 1);
        assertThat(reports.report(MERCHANT, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1))
                        .entries())
                .isEmpty();
    }

    // 완료는 처리일, 예정은 확정 가능일로 집계하고 날짜 미정 금액은 별도 반환한다.
    @Test
    void report_whenStatesMixed_aggregatesWithoutInventingDatesOrItems() {
        captures.processCapture(PAYMENT, MERCHANT, "P6-REPORT-DONE", 10000L);
        pickups.recordCompletion(MERCHANT, "P6-REPORT-DONE", START.minusSeconds(72 * 3600));
        jdbc.update(
                "UPDATE settlements SET status='COMPLETED',completed_at=? WHERE payment_id=?",
                java.sql.Timestamp.from(START.plusSeconds(3600)),
                PAYMENT);
        captures.processCapture(PAYMENT + 1, MERCHANT, "P6-REPORT-SCHEDULED", 20000L);
        pickups.recordCompletion(MERCHANT, "P6-REPORT-SCHEDULED", START.minusSeconds(72 * 3600));
        captures.processCapture(PAYMENT + 2, MERCHANT, "P6-REPORT-UNDATED", 10000L);
        var result = reports.report(MERCHANT, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2));
        assertThat(result.completedAmount()).isEqualTo(9700);
        assertThat(result.scheduledAmount()).isEqualTo(19400);
        assertThat(result.expectedAmount()).isZero();
        assertThat(result.undatedAmount()).isEqualTo(9700);
        assertThat(result.entries())
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.items()).isEmpty());
        assertThat(result.days().getFirst().completedAmount()).isEqualTo(9700);
        assertThat(result.days().getFirst().scheduledAmount()).isEqualTo(19400);
    }
}
