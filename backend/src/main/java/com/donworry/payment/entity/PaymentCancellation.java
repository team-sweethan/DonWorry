package com.donworry.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_cancellations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancellation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long cancellationId;

    @Column(nullable = false, updatable = false)
    private Long paymentId;

    @Column(nullable = false, updatable = false)
    private Long sourceRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentCancellationOperation operation;

    @Column(nullable = false, updatable = false)
    private Long amount;

    @Column(updatable = false)
    private Long captureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentCancellationStatus status;

    @Column(length = 100)
    private String errorCode;

    @Column(nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private Instant acceptedAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant completedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentCancellation(
            PaymentCancellationRequest request,
            Payment payment,
            PaymentCancellationOperation operation,
            Long captureId,
            Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(payment, "payment");
        if (request.getResolution() != PaymentCancellationResolution.ACCEPTED
                || !request.getPaymentId().equals(payment.getPaymentId())
                || !request.getMerchantId().equals(payment.getMerchantId()))
            throw new IllegalArgumentException("접수한 요청과 결제가 일치하지 않습니다");
        this.sourceRequestId = PaymentStorageValues.positive(request.getRequestId());
        this.paymentId = PaymentStorageValues.positive(payment.getPaymentId());
        this.amount = PaymentStorageValues.positive(payment.getAmount());
        this.operation = Objects.requireNonNull(operation, "operation");
        if (operation == PaymentCancellationOperation.REFUND) {
            this.captureId = PaymentStorageValues.positive(captureId);
        } else if (captureId != null) {
            throw new IllegalArgumentException("승인 취소에는 매입 ID가 없습니다");
        }
        this.acceptedAt = PaymentStorageValues.micros(now);
        if (acceptedAt.isBefore(request.getUpdatedAt())) throw new IllegalArgumentException("작업 접수 시각이 요청 판단보다 빠릅니다");
        this.updatedAt = acceptedAt;
        this.status = PaymentCancellationStatus.PROCESSING;
    }

    // 저장 생성만 수행한다. 실행 가능성·P6 매입 근거 확인은 호출 서비스의 책임이다.
    public static PaymentCancellation start(
            PaymentCancellationRequest request,
            Payment payment,
            PaymentCancellationOperation operation,
            Long captureId,
            Instant now) {
        return PaymentCancellation.builder()
                .request(request)
                .payment(payment)
                .operation(operation)
                .captureId(captureId)
                .now(now)
                .build();
    }

    public void markUnknown(Instant now) {
        if (status == PaymentCancellationStatus.UNKNOWN) return;
        requireOpen();
        Instant time = requireTime(now);
        status = PaymentCancellationStatus.UNKNOWN;
        updatedAt = time;
    }

    public void succeed(Instant now) {
        finish(PaymentCancellationStatus.SUCCEEDED, null, now);
    }

    public void fail(String errorCode, Instant now) {
        String code = PaymentStorageValues.reference(errorCode);
        finish(PaymentCancellationStatus.FAILED, code, now);
    }

    private void finish(PaymentCancellationStatus result, String code, Instant now) {
        Instant time = PaymentStorageValues.micros(now);
        if (status == result && Objects.equals(errorCode, code) && time.equals(completedAt)) return;
        requireOpen();
        time = requireTime(time);
        status = result;
        errorCode = code;
        completedAt = time;
        updatedAt = time;
    }

    private void requireOpen() {
        if (status != PaymentCancellationStatus.PROCESSING && status != PaymentCancellationStatus.UNKNOWN)
            throw new IllegalStateException("확정된 취소 결과는 변경할 수 없습니다");
    }

    private Instant requireTime(Instant now) {
        Instant time = PaymentStorageValues.micros(now);
        if (time.isBefore(updatedAt)) throw new IllegalArgumentException("작업 시각이 역전되었습니다");
        return time;
    }
}
