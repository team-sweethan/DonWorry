package com.donworry.settlement.entity;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "settlement_refund_adjustments",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_refund_adjustments_cancellation", columnNames = "cancellation_id"),
            @UniqueConstraint(name = "uk_refund_adjustments_settlement", columnNames = "settlement_id"),
            @UniqueConstraint(name = "uk_refund_adjustments_payment", columnNames = "payment_id")
        })
public class SettlementRefundAdjustment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "adjustment_id")
    private Long adjustmentId;

    @Column(name = "cancellation_id", nullable = false)
    private Long cancellationId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "capture_id", nullable = false)
    private Long captureId;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount;

    @Column(name = "card_fee_return", nullable = false)
    private Long cardFeeReturn;

    @Column(name = "pg_fee_return", nullable = false)
    private Long pgFeeReturn;

    @Column(name = "adjustment_amount", nullable = false)
    private Long adjustmentAmount;

    @Column(name = "remaining_amount", nullable = false)
    private Long remainingAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 30)
    private RefundAdjustmentStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "disposition", length = 30)
    private RefundAdjustmentDisposition disposition;

    @Column(name = "canceled_at", nullable = false)
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SettlementRefundAdjustment received(PaymentRefundSucceeded event, Settlement source, Instant now) {
        if (event == null
                || source == null
                || now == null
                || source.getSettlementId() == null
                || !Objects.equals(event.paymentId(), source.getPaymentId())
                || !Objects.equals(event.captureId(), source.getCaptureId())
                || !Objects.equals(event.merchantId(), source.getMerchantId())
                || !Objects.equals(event.cancellationAmount(), source.getGrossAmount()))
            throw new IllegalArgumentException("환불과 원 정산의 식별자·전액 금액이 일치해야 합니다.");
        long gross = source.getGrossAmount(), card = source.getCardFeeAmount(), pg = source.getPgFeeAmount();
        if (card < 0
                || pg < 0
                || card > gross
                || pg > gross
                || source.getSettlementAmount() < 0
                || gross - card - pg != source.getSettlementAmount())
            throw new IllegalStateException("원 정산의 수수료·순정산액이 일치하지 않습니다.");
        return SettlementRefundAdjustment.builder()
                .event(event)
                .source(source)
                .now(now.truncatedTo(ChronoUnit.MICROS))
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private SettlementRefundAdjustment(PaymentRefundSucceeded event, Settlement source, Instant now) {
        cancellationId = event.cancellationId();
        paymentId = event.paymentId();
        captureId = event.captureId();
        settlementId = source.getSettlementId();
        merchantId = event.merchantId();
        refundAmount = event.cancellationAmount();
        // 현재 요율로 다시 계산하지 않고 원 거래에 저장된 수수료와 순정산액을 보존한다.
        cardFeeReturn = source.getCardFeeAmount();
        pgFeeReturn = source.getPgFeeAmount();
        adjustmentAmount = source.getSettlementAmount();
        remainingAmount = adjustmentAmount;
        status = RefundAdjustmentStatus.PENDING;
        disposition = null;
        canceledAt = event.canceledAt();
        createdAt = now;
        updatedAt = now;
    }

    public boolean matchesRequest(PaymentRefundSucceeded event) {
        return event != null
                && Objects.equals(cancellationId, event.cancellationId())
                && Objects.equals(paymentId, event.paymentId())
                && Objects.equals(captureId, event.captureId())
                && Objects.equals(merchantId, event.merchantId())
                && Objects.equals(refundAmount, event.cancellationAmount())
                && Objects.equals(canceledAt, event.canceledAt());
    }

    public void excludeBeforePayout(Settlement source, Instant now) {
        if (now == null
                || source == null
                || status != RefundAdjustmentStatus.PENDING
                || source.getStatus() != SettlementStatus.SCHEDULED
                || !Objects.equals(settlementId, source.getSettlementId())
                || !Objects.equals(paymentId, source.getPaymentId())
                || !Objects.equals(captureId, source.getCaptureId())
                || !Objects.equals(merchantId, source.getMerchantId())
                || !Objects.equals(refundAmount, source.getGrossAmount())
                || !Objects.equals(cardFeeReturn, source.getCardFeeAmount())
                || !Objects.equals(pgFeeReturn, source.getPgFeeAmount())
                || !Objects.equals(adjustmentAmount, source.getSettlementAmount()))
            throw new IllegalStateException("환불 제외와 원 정산의 근거가 일치하지 않습니다.");
        status = RefundAdjustmentStatus.APPLIED;
        disposition = RefundAdjustmentDisposition.UNPAID_EXCLUSION;
        remainingAmount = 0L;
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
