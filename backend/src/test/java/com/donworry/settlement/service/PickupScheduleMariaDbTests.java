package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.dto.PickupOrderItemRequest;
import com.donworry.settlement.repository.*;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PickupScheduleMariaDbTests {
    private static final long PAYMENT = 92701L;
    private static final String ORDER = "P6-PICKUP-92701";
    private static final Instant AT = Instant.parse("2026-10-27T01:00:00.123456Z");

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    CaptureService captureService;

    @Autowired
    OrderFulfillmentService pickups;

    @Autowired
    OrderFulfillmentRepository orders;

    @Autowired
    SettlementRepository settlements;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM settlements WHERE payment_id IN (?, ?)", PAYMENT, PAYMENT + 1);
        jdbc.update("DELETE FROM captures WHERE payment_id IN (?, ?)", PAYMENT, PAYMENT + 1);
        jdbc.update(
                "DELETE i FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.order_id=?",
                ORDER);
        jdbc.update("DELETE FROM order_fulfillments WHERE order_id = ?", ORDER);
    }

    // 픽업 선도착 기록은 매입 후 연결되며 UTC 마이크로초와 정확한 72시간을 유지한다
    @Test
    void process_whenPickupFirst_linksAfterCapture() {
        pickups.recordSchedule(1L, ORDER, AT, java.util.List.of(new PickupOrderItemRequest("케이크", 1)));
        pickups.recordCompletion(1L, ORDER, AT.plusSeconds(60));
        assertThat(orders.findByOrderId(ORDER).orElseThrow().getMerchantId()).isEqualTo(1L);
        captureService.processCapture(PAYMENT, 1L, ORDER, 10001L);
        assertThat(orders.findByOrderId(ORDER).orElseThrow().getMerchantId()).isEqualTo(1L);
        var s = settlements.findByPaymentId(PAYMENT).orElseThrow();
        assertThat(s.getExpectedSettlementAt()).isEqualTo(AT.plusSeconds(72 * 3600));
        assertThat(s.getScheduledAt()).isEqualTo(AT.plusSeconds(72 * 3600 + 60));
        assertThat(s.getCompletedAt()).isNull();
    }

    // 매입 선도착과 완료 정보 선도착 및 중복 재전달을 모두 처리한다
    @Test
    void process_whenCaptureFirst_updatesExistingSettlement() {
        captureService.processCapture(PAYMENT, 1L, ORDER, 10001L);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getScheduledAt())
                .isNull();
        pickups.recordCompletion(1L, ORDER, AT);
        pickups.recordSchedule(1L, ORDER, AT.plusSeconds(60), java.util.List.of(new PickupOrderItemRequest("케이크", 1)));
        var before = orders.findByOrderId(ORDER).orElseThrow().getUpdatedAt();
        pickups.recordCompletion(1L, ORDER, AT);
        assertThat(orders.findByOrderId(ORDER).orElseThrow().getUpdatedAt()).isEqualTo(before);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getScheduledAt())
                .isEqualTo(AT.plusSeconds(72 * 3600));
    }

    // 충돌 시각과 다른 결제의 동일 주문 매입은 기존 기록을 덮어쓰지 않고 롤백한다
    @Test
    void process_whenConflicting_rollsBackOnlyNewRequest() {
        captureService.processCapture(PAYMENT, 1L, ORDER, 10001L);
        pickups.recordCompletion(1L, ORDER, AT);
        assertThatIllegalStateException().isThrownBy(() -> pickups.recordCompletion(1L, ORDER, AT.plusSeconds(1)));
        assertThatIllegalStateException()
                .isThrownBy(() -> captureService.processCapture(PAYMENT + 1, 2L, ORDER, 10001L));
        assertThat(orders.findByOrderId(ORDER).orElseThrow().getFulfilledAt()).isEqualTo(AT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM captures WHERE payment_id = ?", Long.class, PAYMENT + 1))
                .isZero();
    }

    // 매입과 픽업이 동시에 도착해도 하나의 주문 행에 연결과 일정을 모두 남긴다
    @Test
    void process_whenConcurrent_doesNotLoseLink() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var capture = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return captureService.processCapture(PAYMENT, 1L, ORDER, 10001L);
            });
            var pickup = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                pickups.recordCompletion(1L, ORDER, AT);
                return true;
            });
            capture.get(20, TimeUnit.SECONDS);
            pickup.get(20, TimeUnit.SECONDS);
        }
        assertThat(orders.findByOrderId(ORDER).orElseThrow().getMerchantId()).isEqualTo(1L);
        assertThat(settlements.findByPaymentId(PAYMENT).orElseThrow().getScheduledAt())
                .isEqualTo(AT.plusSeconds(72 * 3600));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_fulfillments WHERE order_id = ?", Long.class, ORDER))
                .isEqualTo(1);
    }
}
