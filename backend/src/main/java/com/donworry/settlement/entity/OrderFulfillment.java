package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "order_fulfillments",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_order_fulfillments_merchant_order",
                        columnNames = {"merchant_id", "order_id"}))
public class OrderFulfillment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fulfillment_id")
    private Long fulfillmentId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId;

    @Column(name = "scheduled_fulfillment_at")
    private Instant scheduledFulfillmentAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // 신규 행의 생성은 Repository의 원자적 주문 행 확보 경로를 사용한다.
    public static OrderFulfillment awaitingPickup(String orderId, Instant now) {
        return OrderFulfillment.builder().orderId(orderId).now(now).build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private OrderFulfillment(String orderId, Instant now) {
        if (orderId == null || orderId.isBlank() || orderId.length() > 100)
            throw new IllegalArgumentException("주문번호가 올바르지 않습니다.");
        this.orderId = orderId;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public void bindMerchant(Long merchantId, Instant now) {
        if (merchantId == null || merchantId <= 0) throw new IllegalArgumentException("가맹점 ID는 양수여야 합니다.");
        if (this.merchantId != null && !this.merchantId.equals(merchantId))
            throw new IllegalStateException("연결된 가맹점이 다릅니다.");
        if (this.merchantId == null) {
            this.merchantId = merchantId;
            this.updatedAt = now;
        }
    }

    public void recordSchedule(Instant at, Instant now) {
        at = requirePrecision(at);
        if (scheduledFulfillmentAt != null && !scheduledFulfillmentAt.equals(at))
            throw new IllegalStateException("픽업 예정 시각이 기존 값과 다릅니다.");
        if (scheduledFulfillmentAt == null) {
            scheduledFulfillmentAt = at;
            updatedAt = now;
        }
    }

    public void recordCompletion(Instant at, Instant now) {
        at = requirePrecision(at);
        if (fulfilledAt != null && !fulfilledAt.equals(at)) throw new IllegalStateException("픽업 완료 시각이 기존 값과 다릅니다.");
        if (fulfilledAt == null) {
            fulfilledAt = at;
            updatedAt = now;
        }
    }

    private static Instant requirePrecision(Instant at) {
        if (at == null || !at.equals(at.truncatedTo(ChronoUnit.MICROS)))
            throw new IllegalArgumentException("픽업 시각은 마이크로초 이하여야 합니다.");
        return at;
    }
}
