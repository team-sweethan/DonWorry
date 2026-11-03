package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "merchant_payouts",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_merchant_payouts_merchant_date",
                        columnNames = {"merchant_id", "payout_date"}))
public class MerchantPayout {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payout_id")
    private Long payoutId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payout_date", nullable = false)
    private LocalDate payoutDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 30)
    private PayoutStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "origin", nullable = false, length = 20)
    private PayoutOrigin origin;

    @Column(name = "settlement_total", nullable = false)
    private Long settlementTotal;

    @Column(name = "refund_deduction_total", nullable = false)
    private Long refundDeductionTotal;

    @Column(name = "payout_amount", nullable = false)
    private Long payoutAmount;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static MerchantPayout pending(Long merchantId, LocalDate payoutDate, Instant now) {
        if (merchantId == null || merchantId <= 0 || payoutDate == null || now == null)
            throw new IllegalArgumentException("가맹점·지급일·생성 시각이 필요합니다.");
        return MerchantPayout.builder()
                .merchantId(merchantId)
                .payoutDate(payoutDate)
                .now(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private MerchantPayout(Long merchantId, LocalDate payoutDate, Instant now) {
        this.merchantId = merchantId;
        this.payoutDate = payoutDate;
        this.status = PayoutStatus.PENDING;
        this.origin = PayoutOrigin.BATCH;
        this.settlementTotal = 0L;
        this.refundDeductionTotal = 0L;
        this.payoutAmount = 0L;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void complete(long total, long deduction, Instant now) {
        if (status != PayoutStatus.PENDING
                || total < 0
                || deduction < 0
                || deduction > total
                || now == null
                || !payoutDate.equals(now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()))
            throw new IllegalStateException("같은 한국 날짜의 진행 중 지급만 확정할 수 있습니다.");
        settlementTotal = total;
        refundDeductionTotal = deduction;
        payoutAmount = total - deduction;
        status = PayoutStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }
}
