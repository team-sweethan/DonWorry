package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.entity.OrderFulfillment;
import com.donworry.settlement.entity.OrderFulfillmentItem;
import com.donworry.settlement.repository.OrderFulfillmentItemRepository;
import com.donworry.settlement.repository.OrderFulfillmentRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class OrderFulfillmentItemMariaDbTests {
    private static final Instant NOW = Instant.parse("2026-10-29T05:00:00.123456Z");

    @MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    OrderFulfillmentRepository orders;

    @Autowired
    OrderFulfillmentItemRepository items;

    @Autowired
    JdbcTemplate jdbc;

    private Long fulfillmentId;

    @BeforeEach
    void createOrder() {
        fulfillmentId = orders.saveAndFlush(OrderFulfillment.awaitingPickup("P6-ITEM-" + UUID.randomUUID(), NOW))
                .getFulfillmentId();
    }

    @AfterEach
    void cleanUp() {
        if (fulfillmentId == null) return;
        jdbc.update("DELETE FROM order_fulfillment_items WHERE fulfillment_id=?", fulfillmentId);
        jdbc.update("DELETE FROM order_fulfillments WHERE fulfillment_id=?", fulfillmentId);
    }

    // 동일 상품명도 서로 다른 항목이면 각각 저장하고 원본 순서대로 읽는다.
    @Test
    void save_whenSameNameAtDifferentPositions_readsBothInOriginalOrder() {
        items.saveAndFlush(OrderFulfillmentItem.snapshot(fulfillmentId, 2, "딸기 케이크", 3, NOW));
        items.saveAndFlush(OrderFulfillmentItem.snapshot(fulfillmentId, 1, "딸기 케이크", 1, NOW));

        var saved = items.findAllByFulfillmentIdOrderByItemSequenceAsc(fulfillmentId);
        assertThat(saved).extracting(OrderFulfillmentItem::getItemSequence).containsExactly(1, 2);
        assertThat(saved).extracting(OrderFulfillmentItem::getQuantity).containsExactly(1, 3);
        assertThat(saved).allSatisfy(item -> {
            assertThat(item.getProductName()).isEqualTo("딸기 케이크");
            assertThat(item.getCreatedAt()).isEqualTo(NOW);
        });
    }

    // 같은 주문의 같은 항목 위치는 DB UNIQUE로 중복 저장을 막는다.
    @Test
    void save_whenPositionDuplicated_rejectsDuplicateAndKeepsExistingItem() {
        items.saveAndFlush(OrderFulfillmentItem.snapshot(fulfillmentId, 1, "딸기 케이크", 1, NOW));
        assertThatThrownBy(() -> items.saveAndFlush(OrderFulfillmentItem.snapshot(fulfillmentId, 1, "초코 케이크", 2, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(items.findAllByFulfillmentIdOrderByItemSequenceAsc(fulfillmentId))
                .hasSize(1);
    }

    // 다른 주문은 같은 항목 순서를 독립적으로 사용할 수 있다.
    @Test
    void save_whenDifferentOrders_reusesPositionWithoutMixingItems() {
        var other = orders.saveAndFlush(OrderFulfillment.awaitingPickup("P6-ITEM-OTHER-" + UUID.randomUUID(), NOW));
        try {
            items.saveAndFlush(OrderFulfillmentItem.snapshot(fulfillmentId, 1, "딸기 케이크", 1, NOW));
            items.saveAndFlush(OrderFulfillmentItem.snapshot(other.getFulfillmentId(), 1, "초코 케이크", 2, NOW));
            assertThat(items.findAllByFulfillmentIdOrderByItemSequenceAsc(fulfillmentId))
                    .extracting(OrderFulfillmentItem::getProductName)
                    .containsExactly("딸기 케이크");
        } finally {
            jdbc.update("DELETE FROM order_fulfillment_items WHERE fulfillment_id=?", other.getFulfillmentId());
            orders.deleteById(other.getFulfillmentId());
        }
    }

    // 앱 검증을 우회한 SQL도 수량·순서·이름 제약으로 거절해야 한다.
    @Test
    void insert_whenInvalidValues_rejectsDatabaseConstraintViolations() {
        String sql =
                "INSERT INTO order_fulfillment_items(fulfillment_id,item_sequence,product_name,quantity,created_at) VALUES (?,?,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(sql, fulfillmentId, 1, "케이크", 0, java.sql.Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, fulfillmentId, 0, "케이크", 1, java.sql.Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, fulfillmentId, 1, " ", 1, java.sql.Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 부모 없는 상품을 만들거나 부모 삭제로 상품 기록을 잃지 않도록 FK를 검사한다.
    @Test
    void insertOrDelete_whenParentIntegrityBroken_rejectsForeignKeyViolation() {
        items.saveAndFlush(OrderFulfillmentItem.snapshot(fulfillmentId, 1, "케이크", 1, NOW));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM order_fulfillments WHERE fulfillment_id=?", fulfillmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> items.saveAndFlush(OrderFulfillmentItem.snapshot(Long.MAX_VALUE, 1, "케이크", 1, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
