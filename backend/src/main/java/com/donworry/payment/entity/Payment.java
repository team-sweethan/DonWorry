package com.donworry.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "customer_ref", nullable = false, length = 100)
    private String customerRef;

    @Column(name = "order_ref", nullable = false, length = 100)
    private String orderRef;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "authorization_ref", length = 100)
    private String authorizationRef;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    public static Payment pending(
            Long merchantId, String customerRef, String orderRef, Long amount, Instant createdAt) {
        return Payment.builder()
                .merchantId(merchantId)
                .customerRef(customerRef)
                .orderRef(orderRef)
                .amount(amount)
                .createdAt(createdAt)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private Payment(Long merchantId, String customerRef, String orderRef, Long amount, Instant createdAt) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.customerRef = requireReference(customerRef, "customerRef");
        this.orderRef = requireReference(orderRef, "orderRef");
        this.amount = Objects.requireNonNull(amount, "amount");
        // DATETIME(6) 왕복 시 생성 직후 값과 재조회 값의 정밀도를 맞춘다.
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MICROS);
        this.status = PaymentStatus.PENDING;
    }

    public void requireUnapprovedPending() {
        if (status != PaymentStatus.PENDING || authorizationRef != null) {
            throw new IllegalStateException("승인 결과가 없는 PENDING 결제에만 적용할 수 있습니다");
        }
    }

    public void deny() {
        requireUnapprovedPending();
        status = PaymentStatus.DENIED;
    }

    public void recordAuthorizationSuccess(String reference, boolean hold) {
        requireUnapprovedPending();
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("authorizationRef must not be blank");
        }
        authorizationRef = requireReference(reference, "authorizationRef");
        status = hold ? PaymentStatus.HELD : PaymentStatus.PENDING;
    }

    public void declineAuthorization() {
        requireUnapprovedPending();
        status = PaymentStatus.DECLINED;
    }

    private static String requireReference(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.codePointCount(0, value.length()) > 100) {
            throw new IllegalArgumentException(name + " must not exceed 100 characters");
        }
        return value;
    }
}
