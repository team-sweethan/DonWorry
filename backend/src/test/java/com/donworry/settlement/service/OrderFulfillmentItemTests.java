package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.donworry.settlement.entity.OrderFulfillmentItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderFulfillmentItemTests {
    private static final Instant NOW = Instant.parse("2026-10-29T05:00:00.123456789Z");

    // 저장된 주문의 식별자와 원본 상품 순서·이름·수량을 보관한다.
    @Test
    void snapshot_whenValid_preservesOriginalItemAndDatabasePrecision() {
        var item = OrderFulfillmentItem.snapshot(1L, 2, "딸기 케이크", 3, NOW);

        assertThat(item.getFulfillmentItemId()).isNull();
        assertThat(item.getFulfillmentId()).isEqualTo(1L);
        assertThat(item.getItemSequence()).isEqualTo(2);
        assertThat(item.getProductName()).isEqualTo("딸기 케이크");
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getCreatedAt()).isEqualTo(Instant.parse("2026-10-29T05:00:00.123456Z"));
    }

    // 잘못된 부모 ID·순서·수량은 SQL 실행 전에 거절한다.
    @Test
    void snapshot_whenIdentifiersOrQuantityInvalid_rejectsItem() {
        assertThatIllegalArgumentException().isThrownBy(() -> OrderFulfillmentItem.snapshot(null, 1, "케이크", 1, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderFulfillmentItem.snapshot(0L, 1, "케이크", 1, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderFulfillmentItem.snapshot(1L, 0, "케이크", 1, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderFulfillmentItem.snapshot(1L, 1, "케이크", 0, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderFulfillmentItem.snapshot(1L, 1, "케이크", -1, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderFulfillmentItem.snapshot(1L, 1, "케이크", 1, null));
    }

    // 상품명 누락은 DTO를 거치지 않는 내부 생성에서도 허용하지 않는다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void snapshot_whenNameMissing_rejectsItem(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> OrderFulfillmentItem.snapshot(1L, 1, name, 1, NOW));
    }

    // CakeShop·요청 DTO·저장 컬럼의 상품명 길이 경계가 같아야 한다.
    @Test
    void snapshot_whenNameLengthAtBoundary_accepts150AndRejects151() {
        assertThat(OrderFulfillmentItem.snapshot(1L, 1, "가".repeat(150), 1, NOW).getProductName())
                .hasSize(150);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderFulfillmentItem.snapshot(1L, 1, "가".repeat(151), 1, NOW));
    }
}
