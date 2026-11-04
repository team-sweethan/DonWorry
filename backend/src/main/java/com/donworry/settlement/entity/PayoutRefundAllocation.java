package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "payout_refund_allocations",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_payout_refund_allocations_pair",
                        columnNames = {"payout_id", "adjustment_id"}))
public class PayoutRefundAllocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "allocation_id")
    private Long allocationId;

    @Column(name = "payout_id", nullable = false)
    private Long payoutId;

    @Column(name = "adjustment_id", nullable = false)
    private Long adjustmentId;

    @Column(name = "applied_amount", nullable = false)
    private Long appliedAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static PayoutRefundAllocation applied(
            MerchantPayout payout, SettlementRefundAdjustment adjustment, long amount, Instant now) {
        if (payout == null
                || adjustment == null
                || payout.getPayoutId() == null
                || adjustment.getAdjustmentId() == null
                || payout.getStatus() != PayoutStatus.PENDING
                || now == null
                || amount <= 0
                || adjustment.getStatus() != RefundAdjustmentStatus.APPLIED
                || adjustment.getDisposition() != RefundAdjustmentDisposition.RECOVERY
                || !Objects.equals(payout.getMerchantId(), adjustment.getMerchantId())
                || amount > adjustment.getRemainingAmount())
            throw new IllegalStateException("같은 가맹점의 미회수액만 진행 중 지급에 배분할 수 있습니다.");
        return PayoutRefundAllocation.builder()
                .payoutId(payout.getPayoutId())
                .adjustmentId(adjustment.getAdjustmentId())
                .amount(amount)
                .now(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private PayoutRefundAllocation(Long payoutId, Long adjustmentId, long amount, Instant now) {
        this.payoutId = payoutId;
        this.adjustmentId = adjustmentId;
        appliedAmount = amount;
        createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
