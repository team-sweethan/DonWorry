package com.donworry.payment.entity;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long paymentOrderId;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 100)
    private String orderRef;

    @Column(nullable = false, length = 100)
    private String customerRef;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant orderCreatedAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant paymentDeadlineAt;

    @Column(nullable = false)
    private long lastAttemptNo;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentOrder(
            Long merchantId,
            String orderRef,
            String customerRef,
            Long amount,
            Instant orderCreatedAt,
            Instant createdAt) {
        this.merchantId = PaymentStorageValues.positive(merchantId);
        this.orderRef = PaymentStorageValues.reference(orderRef);
        this.customerRef = PaymentStorageValues.reference(customerRef);
        this.amount = PaymentStorageValues.positive(amount);
        this.orderCreatedAt = PaymentStorageValues.micros(orderCreatedAt);
        this.createdAt = PaymentStorageValues.micros(createdAt);
        if (this.orderCreatedAt.isAfter(this.createdAt)) throw new IllegalArgumentException("미래 주문 시각입니다");
        this.paymentDeadlineAt = this.orderCreatedAt.plus(Duration.ofMinutes(10));
    }

    public static PaymentOrder register(
            Long merchantId, String orderRef, String customerRef, Long amount, Instant orderCreatedAt, Instant now) {
        return PaymentOrder.builder()
                .merchantId(merchantId)
                .orderRef(orderRef)
                .customerRef(customerRef)
                .amount(amount)
                .orderCreatedAt(orderCreatedAt)
                .createdAt(now)
                .build();
    }

    public boolean matches(String orderRef, String customerRef, Long amount, Instant orderCreatedAt) {
        return this.orderRef.equals(orderRef)
                && this.customerRef.equals(customerRef)
                && this.amount.equals(amount)
                && this.orderCreatedAt.equals(PaymentStorageValues.micros(orderCreatedAt));
    }

    // 호출 서비스가 주문 행 잠금과 이전 시도의 최종 거절을 확인한 뒤에만 사용한다.
    public long allocateAttempt(Instant now) {
        if (!PaymentStorageValues.micros(now).isBefore(paymentDeadlineAt))
            throw new IllegalStateException("새 결제 시도 접수 기한이 지났습니다");
        lastAttemptNo = Math.incrementExact(lastAttemptNo);
        return lastAttemptNo;
    }
}
