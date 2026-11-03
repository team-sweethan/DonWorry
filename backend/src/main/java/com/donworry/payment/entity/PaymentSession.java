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
    // 첫 시도부터 약 24시간(10·20·40·80·160초 뒤 5분 간격으로 293번째 시도)이면 그 상태의 알림을 그만둔다.
    // 가맹점은 조회 배치로도 상태를 맞추므로 웹훅은 빠른 알림일 뿐이다 (ADR-0064 3절, #455)
    public static final int NOTIFY_MAX_ATTEMPTS = 293;

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

    // 처음 결제를 누른 값. 접수 뒤 평가·승인 중 실패해도 재시도는 이 값으로 같은 결제 시도를 이어 간다(카드를 바꿔도 무시)
    @Column(length = 1)
    private String submittedCard;

    @Column(length = 100)
    private String submittedDeviceId;

    @Column(length = 45)
    private String submittedIp;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant submittedAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private Instant createdAt;

    @Column(length = 30)
    private String notifiedStatus;

    private Boolean notifiedAuthorized;

    @Column(nullable = false)
    private int notifyAttempts;

    // 지금 재시도 중인 웹훅의 eventId. 보낼 eventId가 바뀌면(재시도 중 상태 변경) 횟수를 새로 센다 (#455)
    @Column(length = 100)
    private String notifyEventId;

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

    // 처음 결제한 카드·기기·IP를 고정한다. 이미 고정됐으면 바꾸지 않는다
    public void fixAttempt(String card, String deviceId, String ip, Instant now) {
        if (submittedAt != null) return;
        this.submittedCard = Objects.requireNonNull(card, "card");
        this.submittedDeviceId = deviceId;
        this.submittedIp = ip;
        this.submittedAt = PaymentStorageValues.micros(now);
    }

    public boolean attemptFixed() {
        return submittedAt != null;
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
        this.notifyEventId = null;
        this.nextNotifyAt = null;
    }

    // 보내지 못한 웹훅(eventId · 상태)을 받아, 한도에 닿으면 그 상태를 처리한 것으로 기록하고 그만둔다(true).
    // 재시도 중 상태가 바뀌어 eventId가 달라지면 앞 상태의 실패를 이어 세지 않고 횟수 · 간격을 처음부터 센다.
    // 처리한 것으로 기록해야 다음 주기에 같은 상태로 다시 예약되지 않고, 상태가 또 바뀌면 새로 알린다.
    // notifyAttempts는 남겨 두어 중단된 세션(notifyAttempts > 0, nextNotifyAt 없음)을 운영에서 찾을 수 있게 한다
    public boolean notifyFailed(String eventId, String status, boolean authorized, Instant now) {
        if (!Objects.equals(notifyEventId, eventId)) {
            notifyEventId = Objects.requireNonNull(eventId, "eventId");
            notifyAttempts = 0;
        }
        notifyAttempts++;
        if (notifyAttempts >= NOTIFY_MAX_ATTEMPTS) {
            this.notifiedStatus = status;
            this.notifiedAuthorized = authorized;
            this.nextNotifyAt = null;
            return true;
        }
        long factor = 1L << Math.min(notifyAttempts, 6);
        Duration delay = NOTIFY_BASE_DELAY.multipliedBy(factor);
        if (delay.compareTo(NOTIFY_MAX_DELAY) > 0) delay = NOTIFY_MAX_DELAY;
        nextNotifyAt = PaymentStorageValues.micros(now.plus(delay));
        return false;
    }
}
