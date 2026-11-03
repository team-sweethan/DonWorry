package com.donworry.settlement.entity;

import jakarta.persistence.*;
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
        name = "captures",
        uniqueConstraints = {@UniqueConstraint(name = "uk_captures_payment", columnNames = "payment_id")})
public class Capture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "capture_id")
    private Long captureId;

    // 모듈 간 직접 참조를 피하기 위해 P5 결제는 ID만 보관한다.
    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId; // P5 tenant_id (가맹점 ID)

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId; // CakeShop orders.order_number

    @Column(name = "capture_amount", nullable = false)
    private Long captureAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 30)
    private CaptureStatus status;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Capture processing(Long paymentId, Long merchantId, String orderId, Long captureAmount, Instant now) {
        return Capture.builder()
                .paymentId(paymentId)
                .merchantId(merchantId)
                .orderId(orderId)
                .captureAmount(captureAmount)
                .now(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private Capture(Long paymentId, Long merchantId, String orderId, Long captureAmount, Instant now) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.captureAmount = captureAmount;
        this.status = CaptureStatus.PROCESSING;
        this.retryCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        if (this.status != CaptureStatus.PROCESSING) {
            throw new IllegalStateException("매입 처리 중인 건만 완료할 수 있습니다.");
        }

        this.status = CaptureStatus.COMPLETED;
        this.capturedAt = now;
        this.failureReason = null;
        this.updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        if (this.status != CaptureStatus.PROCESSING) {
            throw new IllegalStateException("매입 처리 중인 건만 실패 처리할 수 있습니다.");
        }

        this.status = CaptureStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public void retry(Instant now) {
        if (this.status != CaptureStatus.FAILED) {
            throw new IllegalStateException("매입 실패 건만 재처리할 수 있습니다.");
        }

        this.status = CaptureStatus.PROCESSING;
        this.retryCount++;
        this.failureReason = null;
        this.updatedAt = now;
    }
}
