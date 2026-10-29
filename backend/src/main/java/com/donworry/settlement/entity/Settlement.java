package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "settlements",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_settlements_capture", columnNames = "capture_id"),
            @UniqueConstraint(name = "uk_settlements_payment", columnNames = "payment_id")
        })
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "capture_id", nullable = false)
    private Long captureId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    // 재조회 시에도 당시 계산을 재현할 수 있도록 적용한 수수료율을 보관한다.
    @Column(name = "card_fee_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal cardFeeRate;

    @Column(name = "pg_fee_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal pgFeeRate;

    @Column(name = "card_fee_amount", nullable = false)
    private Long cardFeeAmount;

    @Column(name = "pg_fee_amount", nullable = false)
    private Long pgFeeAmount;

    @Column(name = "settlement_amount", nullable = false)
    private Long settlementAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 30)
    private SettlementStatus status;

    @Column(name = "expected_settlement_at")
    private Instant expectedSettlementAt;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public void applyPickupSchedule(Instant expectedAt, Instant eligibleAt, Instant now) {
        if (java.util.Objects.equals(expectedSettlementAt, expectedAt)
                && java.util.Objects.equals(scheduledAt, eligibleAt)) return;
        if (status != SettlementStatus.SCHEDULED) throw new IllegalStateException("최종 정산의 일정은 변경할 수 없습니다.");
        expectedSettlementAt = expectedAt;
        scheduledAt = eligibleAt;
        updatedAt = now;
    }

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Settlement scheduled(
            Long captureId,
            Long paymentId,
            Long merchantId,
            Long grossAmount,
            BigDecimal cardFeeRate,
            BigDecimal pgFeeRate,
            Long cardFeeAmount,
            Long pgFeeAmount,
            Long settlementAmount,
            Instant now) {
        return Settlement.builder()
                .captureId(captureId)
                .paymentId(paymentId)
                .merchantId(merchantId)
                .grossAmount(grossAmount)
                .cardFeeRate(cardFeeRate)
                .pgFeeRate(pgFeeRate)
                .cardFeeAmount(cardFeeAmount)
                .pgFeeAmount(pgFeeAmount)
                .settlementAmount(settlementAmount)
                .now(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private Settlement(
            Long captureId,
            Long paymentId,
            Long merchantId,
            Long grossAmount,
            BigDecimal cardFeeRate,
            BigDecimal pgFeeRate,
            Long cardFeeAmount,
            Long pgFeeAmount,
            Long settlementAmount,
            Instant now) {
        this.captureId = captureId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.grossAmount = grossAmount;
        this.cardFeeRate = cardFeeRate;
        this.pgFeeRate = pgFeeRate;
        this.cardFeeAmount = cardFeeAmount;
        this.pgFeeAmount = pgFeeAmount;
        this.settlementAmount = settlementAmount;
        this.status = SettlementStatus.SCHEDULED;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
