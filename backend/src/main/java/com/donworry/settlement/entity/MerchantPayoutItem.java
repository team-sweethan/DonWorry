package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "merchant_payout_items",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_merchant_payout_items_settlement", columnNames = "settlement_id"))
public class MerchantPayoutItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payout_item_id")
    private Long payoutItemId;

    @Column(name = "payout_id", nullable = false)
    private Long payoutId;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "settlement_amount", nullable = false)
    private Long settlementAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static MerchantPayoutItem snapshot(Long payoutId, Long settlementId, Long amount, Instant now) {
        if (payoutId == null
                || payoutId <= 0
                || settlementId == null
                || settlementId <= 0
                || amount == null
                || amount < 0
                || now == null) throw new IllegalArgumentException("지급·정산 식별자와 원 단위 금액이 필요합니다.");
        return MerchantPayoutItem.builder()
                .payoutId(payoutId)
                .settlementId(settlementId)
                .settlementAmount(amount)
                .now(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private MerchantPayoutItem(Long payoutId, Long settlementId, Long settlementAmount, Instant now) {
        this.payoutId = payoutId;
        this.settlementId = settlementId;
        this.settlementAmount = settlementAmount;
        this.createdAt = now;
    }
}
