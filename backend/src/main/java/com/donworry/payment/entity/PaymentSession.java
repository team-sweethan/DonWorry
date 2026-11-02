package com.donworry.payment.entity;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 돈워리 호스팅 결제창 세션. 가맹점 서버가 준비하고 구매자가 세션 키로 결제창을 연다.
@Entity
@Table(name = "payment_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentSession {
    // 웹훅 재전송 간격. 실패할수록 늘린다(최대 5분).
    private static final Duration NOTIFY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration NOTIFY_MAX_DELAY = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sessionId;

    @Column(nullable = false, length = 64, updatable = false)
    private String sessionKey;

    @Column(nullable = false, updatable = false)
    private Long merchantId;

    @Column(nullable = false, updatable = false)
    private UUID requestKey;

    // 결제창에서 결제할 때 결제 접수(PaymentRequestCommand)의 멱등 키로 쓴다. 같은 세션의 재요청은 같은 시도다.
    @Column(nullable = false, updatable = false)
    private UUID attemptKey;

    @Column(nullable = false, length = 100, updatable = false)
    private String orderRef;

    @Column(nullable = false, length = 19, updatable = false)
    private String customerRef;

    @Column(nullable = false, updatable = false)
    private Long amount;

    @Column(nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private Instant orderCreatedAt;

    @Column(nullable = false, length = 100, updatable = false)
    private String orderName;

    @Column(length = 254, updatable = false)
    private String buyerEmail;

    @Column(nullable = false, length = 500, updatable = false)
    private String successUrl;

    @Column(nullable = false, length = 500, updatable = false)
    private String failUrl;

    @Column(length = 500, updatable = false)
    private String notifyUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentSessionStatus status;

    private Long paymentId;

    @Column(nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private Instant createdAt;

    @Column(length = 30)
    private String notifiedStatus;

    private Boolean notifiedAuthorized;

    @Column(nullable = false)
    private int notifyAttempts;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant nextNotifyAt;

    @Builder
    private PaymentSession(
            String sessionKey,
            Long merchantId,
            UUID requestKey,
            UUID attemptKey,
            String orderRef,
            String customerRef,
            Long amount,
            Instant orderCreatedAt,
            String orderName,
            String buyerEmail,
            String successUrl,
            String failUrl,
            String notifyUrl,
            Instant expiresAt,
            Instant createdAt) {
        this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
        this.merchantId = PaymentStorageValues.positive(merchantId);
        this.requestKey = Objects.requireNonNull(requestKey, "requestKey");
        this.attemptKey = Objects.requireNonNull(attemptKey, "attemptKey");
        this.orderRef = Objects.requireNonNull(orderRef, "orderRef");
        this.customerRef = Objects.requireNonNull(customerRef, "customerRef");
        this.amount = PaymentStorageValues.positive(amount);
        this.orderCreatedAt = PaymentStorageValues.micros(orderCreatedAt);
        this.orderName = Objects.requireNonNull(orderName, "orderName");
        this.buyerEmail = buyerEmail;
        this.successUrl = Objects.requireNonNull(successUrl, "successUrl");
        this.failUrl = Objects.requireNonNull(failUrl, "failUrl");
        this.notifyUrl = notifyUrl;
        this.expiresAt = PaymentStorageValues.micros(expiresAt);
        this.createdAt = PaymentStorageValues.micros(createdAt);
        this.status = PaymentSessionStatus.OPEN;
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    // 같은 멱등 키로 다시 준비할 때 처음 내용과 같은지 확인한다.
    public boolean sameRequest(
            String orderRef,
            String customerRef,
            Long amount,
            Instant orderCreatedAt,
            String orderName,
            String buyerEmail,
            String successUrl,
            String failUrl,
            String notifyUrl) {
        return this.orderRef.equals(orderRef)
                && this.customerRef.equals(customerRef)
                && this.amount.equals(amount)
                && this.orderCreatedAt.equals(PaymentStorageValues.micros(orderCreatedAt))
                && this.orderName.equals(orderName)
                && Objects.equals(this.buyerEmail, buyerEmail)
                && this.successUrl.equals(successUrl)
                && this.failUrl.equals(failUrl)
                && Objects.equals(this.notifyUrl, notifyUrl);
    }

    public void submitted(Long paymentId, Instant now) {
        if (status == PaymentSessionStatus.SUBMITTED) {
            if (!this.paymentId.equals(paymentId)) throw new IllegalStateException("다른 결제와 연결된 세션입니다");
            return;
        }
        this.paymentId = PaymentStorageValues.positive(paymentId);
        this.status = PaymentSessionStatus.SUBMITTED;
        // 알림 주소가 있으면 바로 첫 알림을 예약한다.
        if (notifyUrl != null) this.nextNotifyAt = PaymentStorageValues.micros(now);
    }

    // 결제 상태가 마지막 알림과 달라졌으면 다시 알리도록 예약한다.
    public void scheduleNotifyIfChanged(String status, boolean authorized, Instant now) {
        if (notifyUrl == null || paymentId == null) return;
        if (status.equals(notifiedStatus) && Boolean.valueOf(authorized).equals(notifiedAuthorized)) return;
        if (nextNotifyAt == null) {
            nextNotifyAt = PaymentStorageValues.micros(now);
            notifyAttempts = 0;
        }
    }

    public void notified(String status, boolean authorized) {
        this.notifiedStatus = status;
        this.notifiedAuthorized = authorized;
        this.notifyAttempts = 0;
        this.nextNotifyAt = null;
    }

    public void notifyFailed(Instant now) {
        notifyAttempts++;
        long factor = 1L << Math.min(notifyAttempts, 6);
        Duration delay = NOTIFY_BASE_DELAY.multipliedBy(factor);
        if (delay.compareTo(NOTIFY_MAX_DELAY) > 0) delay = NOTIFY_MAX_DELAY;
        nextNotifyAt = PaymentStorageValues.micros(now.plus(delay));
    }
}
