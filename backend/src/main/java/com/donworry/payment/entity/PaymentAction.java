package com.donworry.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long actionId;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long decisionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentActionType actionType;

    @Column(nullable = false)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentActionStatus status;

    private Long captureId;

    @Column(length = 100)
    private String errorCode;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant requestedAt;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant completedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentAction(
            Long paymentId, Long decisionId, PaymentActionType actionType, UUID idempotencyKey, Instant requestedAt) {
        this.paymentId = positive(paymentId);
        this.decisionId = positive(decisionId);
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestedAt = micros(requestedAt);
        this.status = PaymentActionStatus.PENDING;
    }

    public static PaymentAction pending(
            Long paymentId, Long decisionId, PaymentActionType actionType, UUID idempotencyKey, Instant requestedAt) {
        return PaymentAction.builder()
                .paymentId(paymentId)
                .decisionId(decisionId)
                .actionType(actionType)
                .idempotencyKey(idempotencyKey)
                .requestedAt(requestedAt)
                .build();
    }

    public boolean matches(Long paymentId, Long decisionId, PaymentActionType actionType, UUID idempotencyKey) {
        return this.paymentId.equals(paymentId)
                && this.decisionId.equals(decisionId)
                && this.actionType == actionType
                && this.idempotencyKey.equals(idempotencyKey);
    }

    public void completeCapture(Long captureId, Instant capturedAt) {
        if (actionType != PaymentActionType.CAPTURE) throw new IllegalStateException("CAPTURE 조치가 아닙니다");
        Long validId = positive(captureId);
        Instant time = micros(capturedAt);
        if (status == PaymentActionStatus.SUCCEEDED && validId.equals(this.captureId) && time.equals(completedAt))
            return;
        requirePending();
        this.captureId = validId;
        this.completedAt = time;
        this.status = PaymentActionStatus.SUCCEEDED;
    }

    public void completeVoid(Instant now) {
        if (actionType != PaymentActionType.VOID) throw new IllegalStateException("VOID 조치가 아닙니다");
        Instant time = micros(now);
        requirePending();
        this.completedAt = time;
        this.status = PaymentActionStatus.SUCCEEDED;
    }

    public void fail(String errorCode, Instant now) {
        if (errorCode == null || errorCode.isBlank() || errorCode.codePointCount(0, errorCode.length()) > 100)
            throw new IllegalArgumentException("업무 실패 코드가 필요합니다");
        Instant time = micros(now);
        requirePending();
        this.errorCode = errorCode;
        this.completedAt = time;
        this.status = PaymentActionStatus.FAILED;
    }

    private void requirePending() {
        if (status != PaymentActionStatus.PENDING) throw new IllegalStateException("종료된 조치는 변경할 수 없습니다");
    }

    private static Long positive(Long value) {
        if (value == null || value <= 0) throw new IllegalArgumentException("ID는 양수여야 합니다");
        return value;
    }

    private static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "time").truncatedTo(ChronoUnit.MICROS);
    }
}
