package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.dto.PickupOrderItemRequest;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.entity.OrderFulfillmentItem;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.OrderFulfillmentItemRepository;
import com.donworry.settlement.repository.OrderFulfillmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class OrderFulfillmentItemStorageMariaDbTests {
    private static final Instant AT = Instant.parse("2026-11-01T06:00:00.123456Z");
    private static final long MERCHANT = 806001L;
    private static final List<PickupOrderItemRequest> ITEMS =
            List.of(new PickupOrderItemRequest("딸기 케이크", 2), new PickupOrderItemRequest("초코 케이크", 1));
    private final String orderId = "P6-ITEM-STORAGE-" + UUID.randomUUID();

    @MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    OrderFulfillmentService service;

    @Autowired
    OrderFulfillmentRepository orders;

    @Autowired
    OrderFulfillmentItemRepository items;

    @Autowired
    CaptureRepository captures;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update(
                "DELETE i FROM order_fulfillment_items i JOIN order_fulfillments f ON f.fulfillment_id=i.fulfillment_id WHERE f.order_id=?",
                orderId);
        jdbc.update("DELETE FROM captures WHERE merchant_id=? AND order_id=?", MERCHANT, orderId);
        jdbc.update("DELETE FROM order_fulfillments WHERE order_id=?", orderId);
    }

    private List<OrderFulfillmentItem> saved() {
        var order = orders.findByMerchantIdAndOrderId(MERCHANT, orderId).orElseThrow();
        return items.findAllByFulfillmentIdOrderByItemSequenceAsc(order.getFulfillmentId());
    }

    // 같은 요청은 기존 상품 ID와 최초 저장 시각까지 그대로 유지한다.
    @Test
    void recordSchedule_whenRepeated_storesScheduleAndItemsOnlyOnce() {
        assertThat(service.recordSchedule(MERCHANT, orderId, AT, ITEMS).linkStatus())
                .isEqualTo("PENDING_CAPTURE");
        var before = saved();
        var updatedAt = orders.findByMerchantIdAndOrderId(MERCHANT, orderId)
                .orElseThrow()
                .getUpdatedAt();
        service.recordSchedule(MERCHANT, orderId, AT, ITEMS);
        assertThat(saved())
                .extracting(OrderFulfillmentItem::getFulfillmentItemId)
                .containsExactlyElementsOf(before.stream()
                        .map(OrderFulfillmentItem::getFulfillmentItemId)
                        .toList());
        assertThat(saved()).extracting(OrderFulfillmentItem::getProductName).containsExactly("딸기 케이크", "초코 케이크");
        assertThat(saved()).extracting(OrderFulfillmentItem::getQuantity).containsExactly(2, 1);
        assertThat(saved())
                .extracting(OrderFulfillmentItem::getCreatedAt)
                .containsExactlyElementsOf(
                        before.stream().map(OrderFulfillmentItem::getCreatedAt).toList());
        assertThat(orders.findByMerchantIdAndOrderId(MERCHANT, orderId)
                        .orElseThrow()
                        .getUpdatedAt())
                .isEqualTo(updatedAt);
    }

    // 충돌 재전달은 이름·수량·순서·건수를 바꾸지 않고 롤백한다.
    @Test
    void recordSchedule_whenPayloadChanged_keepsCommittedSnapshot() {
        service.recordSchedule(MERCHANT, orderId, AT, ITEMS);
        var variants = List.of(
                List.of(new PickupOrderItemRequest("변경", 2), ITEMS.get(1)),
                List.of(new PickupOrderItemRequest("딸기 케이크", 3), ITEMS.get(1)),
                List.of(ITEMS.get(1), ITEMS.get(0)),
                List.of(ITEMS.get(0)));
        for (var variant : variants) {
            assertThatThrownBy(() -> service.recordSchedule(MERCHANT, orderId, AT, variant))
                    .isInstanceOfSatisfying(
                            SettlementRequestException.class,
                            exception -> assertThat(exception.getStatus()).isEqualTo(409));
        }
        assertThat(saved()).extracting(OrderFulfillmentItem::getProductName).containsExactly("딸기 케이크", "초코 케이크");
        assertThat(saved()).extracting(OrderFulfillmentItem::getQuantity).containsExactly(2, 1);
        assertThat(orders.findByMerchantIdAndOrderId(MERCHANT, orderId)
                        .orElseThrow()
                        .getScheduledFulfillmentAt())
                .isEqualTo(AT);
    }

    // 완료가 먼저 도착해도 예정 정보의 상품 목록을 채우며 이후 완료는 상품을 건드리지 않는다.
    @Test
    void recordSchedule_whenCompletionArrivedFirst_preservesCompletionAndItems() {
        service.recordCompletion(MERCHANT, orderId, AT.plusSeconds(60));
        service.recordSchedule(MERCHANT, orderId, AT, ITEMS);
        var ids =
                saved().stream().map(OrderFulfillmentItem::getFulfillmentItemId).toList();
        service.recordCompletion(MERCHANT, orderId, AT.plusSeconds(60));
        assertThat(orders.findByMerchantIdAndOrderId(MERCHANT, orderId)
                        .orElseThrow()
                        .getFulfilledAt())
                .isEqualTo(AT.plusSeconds(60));
        assertThat(saved())
                .extracting(OrderFulfillmentItem::getFulfillmentItemId)
                .containsExactlyElementsOf(ids);
    }

    // 상품 INSERT 이후 매입 연결에서 실패해도 주문·시각·상품을 모두 함께 롤백한다.
    @Test
    void recordSchedule_whenLinkFails_rollsBackNewOrderAndEveryItem() {
        long countBefore = items.count();
        captures.saveAndFlush(Capture.processing(8060001L, MERCHANT, orderId, 10000L, AT));
        captures.saveAndFlush(Capture.processing(8060002L, MERCHANT, orderId, 10000L, AT));
        assertThatIllegalStateException().isThrownBy(() -> service.recordSchedule(MERCHANT, orderId, AT, ITEMS));
        assertThat(orders.findByMerchantIdAndOrderId(MERCHANT, orderId)).isEmpty();
        assertThat(items.count()).isEqualTo(countBefore);
    }

    // 기존 완료 기록은 보존하면서 실패한 예정 시각과 상품 INSERT만 롤백한다.
    @Test
    void recordSchedule_whenLinkFailsAfterCompletion_keepsPreviouslyCommittedTime() {
        service.recordCompletion(MERCHANT, orderId, AT.plusSeconds(60));
        captures.saveAndFlush(Capture.processing(8060001L, MERCHANT, orderId, 10000L, AT));
        captures.saveAndFlush(Capture.processing(8060002L, MERCHANT, orderId, 10000L, AT));
        assertThatIllegalStateException().isThrownBy(() -> service.recordSchedule(MERCHANT, orderId, AT, ITEMS));
        var order = orders.findByMerchantIdAndOrderId(MERCHANT, orderId).orElseThrow();
        assertThat(order.getFulfilledAt()).isEqualTo(AT.plusSeconds(60));
        assertThat(order.getScheduledFulfillmentAt()).isNull();
        assertThat(saved()).isEmpty();
    }

    // 같은 주문번호를 먼저 보내도 다른 가맹점의 상품·시각을 선점하지 못한다.
    @Test
    void recordSchedule_whenMerchantDiffers_storesIndependentSnapshot() {
        service.recordSchedule(MERCHANT, orderId, AT, ITEMS);
        service.recordSchedule(
                MERCHANT + 1, orderId, AT.plusSeconds(60), List.of(new PickupOrderItemRequest("다른 상품", 3)));
        assertThat(saved()).extracting(OrderFulfillmentItem::getProductName).containsExactly("딸기 케이크", "초코 케이크");
        var other = orders.findByMerchantIdAndOrderId(MERCHANT + 1, orderId).orElseThrow();
        assertThat(other.getFulfillmentId())
                .isNotEqualTo(orders.findByMerchantIdAndOrderId(MERCHANT, orderId)
                        .orElseThrow()
                        .getFulfillmentId());
        assertThat(items.findAllByFulfillmentIdOrderByItemSequenceAsc(other.getFulfillmentId()))
                .extracting(OrderFulfillmentItem::getProductName)
                .containsExactly("다른 상품");
    }

    // 소유자를 모르는 기존 행은 자동 귀속하지 않고 과거 픽업 시각도 그대로 받는다.
    @Test
    void recordSchedule_whenLegacyUnboundOrderExists_preservesLegacyAndAcceptsPastTime() {
        jdbc.update(
                "INSERT INTO order_fulfillments(order_id,created_at,updated_at) VALUES (?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                orderId);
        var past = Instant.parse("2020-01-01T00:00:00Z");
        service.recordSchedule(MERCHANT, orderId, past, ITEMS);
        assertThat(orders.findByMerchantIdAndOrderId(MERCHANT, orderId)
                        .orElseThrow()
                        .getScheduledFulfillmentAt())
                .isEqualTo(past);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_fulfillments WHERE order_id=? AND merchant_id IS NULL AND scheduled_fulfillment_at IS NULL",
                        Long.class,
                        orderId))
                .isEqualTo(1L);
    }

    // 동시 동일 요청도 DB의 부모 행 잠금으로 직렬화해 항목 수가 늘어나지 않는다.
    @Test
    void recordSchedule_whenConcurrentDuplicate_storesOneSnapshot() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.recordSchedule(MERCHANT, orderId, AT, ITEMS);
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.recordSchedule(MERCHANT, orderId, AT, ITEMS);
            });
            assertThat(first.get(20, TimeUnit.SECONDS).result()).isEqualTo("STORED");
            assertThat(second.get(20, TimeUnit.SECONDS).result()).isEqualTo("STORED");
        }
        assertThat(saved()).hasSize(2);
    }
}
