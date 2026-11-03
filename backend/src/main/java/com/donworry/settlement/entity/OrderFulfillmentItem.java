package com.donworry.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "order_fulfillment_items",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_order_fulfillment_items_sequence",
                        columnNames = {"fulfillment_id", "item_sequence"}))
public class OrderFulfillmentItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fulfillment_item_id")
    private Long fulfillmentItemId;

    @Column(name = "fulfillment_id", nullable = false)
    private Long fulfillmentId;

    @Column(name = "item_sequence", nullable = false)
    private int itemSequence;

    @Column(name = "product_name", nullable = false, length = 150)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static OrderFulfillmentItem snapshot(
            Long fulfillmentId, int itemSequence, String productName, int quantity, Instant now) {
        return OrderFulfillmentItem.builder()
                .fulfillmentId(fulfillmentId)
                .itemSequence(itemSequence)
                .productName(productName)
                .quantity(quantity)
                .now(now)
                .build();
    }

    // 이후 상품명이 변경되어도 주문 당시 정보를 재현하도록 변경 메서드를 제공하지 않는다.
    @Builder(access = AccessLevel.PRIVATE)
    private OrderFulfillmentItem(Long fulfillmentId, int itemSequence, String productName, int quantity, Instant now) {
        if (fulfillmentId == null || fulfillmentId <= 0 || itemSequence <= 0)
            throw new IllegalArgumentException("주문 저장 ID와 항목 순서는 양수여야 합니다.");
        if (productName == null || productName.isBlank() || productName.length() > 150)
            throw new IllegalArgumentException("상품명은 공백 없이 150자 이하여야 합니다.");
        if (quantity <= 0) throw new IllegalArgumentException("상품 수량은 양수여야 합니다.");
        if (now == null) throw new IllegalArgumentException("저장 시각이 필요합니다.");
        this.fulfillmentId = fulfillmentId;
        this.itemSequence = itemSequence;
        this.productName = productName;
        this.quantity = quantity;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
