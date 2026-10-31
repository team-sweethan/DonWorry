package com.donworry.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_cancellation_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancellationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long requestId;

    @Column(nullable = false, updatable = false)
    private Long merchantId;

    @Column(nullable = false, updatable = false)
    private UUID requestKey;

    @Column(nullable = false, updatable = false)
    private Long paymentId;

    @Column(nullable = false, length = 200, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentCancellationResolution resolution;

    @Column(length = 100)
    private String responseCode;

    private Long existingVoidActionId;

    @Column(nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private Instant createdAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentCancellationRequest(Payment payment, UUID requestKey, String reason, Instant now) {
        Objects.requireNonNull(payment, "payment");
        this.paymentId = PaymentStorageValues.positive(payment.getPaymentId());
        this.merchantId = PaymentStorageValues.positive(payment.getMerchantId());
        this.requestKey = Objects.requireNonNull(requestKey, "requestKey");
        this.reason = requireReason(reason);
        this.createdAt = PaymentStorageValues.micros(now);
        this.updatedAt = createdAt;
    }

    // ACCEPTED/LINKED의 작업 존재는 접수 서비스가 같은 트랜잭션에서 보장해야 한다.
    public static PaymentCancellationRequest recordDecision(
            Payment payment,
            UUID key,
            String reason,
            PaymentCancellationResolution resolution,
            PaymentAction existingVoid,
            Instant now) {
        var request = PaymentCancellationRequest.builder()
                .payment(payment)
                .requestKey(key)
                .reason(reason)
                .now(now)
                .build();
        request.applyDecision(payment, resolution, existingVoid, now);
        return request;
    }

    public boolean matches(Long paymentId, String reason) {
        return this.paymentId.equals(paymentId) && this.reason.equals(reason);
    }

    // 조회에서는 호출하지 않는다. 명시적 재요청의 최신 판단만 반영한다.
    public void reconsider(
            Payment payment, PaymentCancellationResolution resolution, PaymentAction existingVoid, Instant now) {
        if (this.resolution != PaymentCancellationResolution.DEFERRED
                && this.resolution != PaymentCancellationResolution.REVIEW_REQUIRED)
            throw new IllegalStateException("확정된 접수 판단은 변경할 수 없습니다");
        applyDecision(payment, resolution, existingVoid, now);
    }

    private void applyDecision(
            Payment payment, PaymentCancellationResolution resolution, PaymentAction existingVoid, Instant now) {
        Objects.requireNonNull(resolution, "resolution");
        if (!paymentId.equals(payment.getPaymentId()) || !merchantId.equals(payment.getMerchantId()))
            throw new IllegalArgumentException("요청과 결제 소유가 일치하지 않습니다");
        Instant time = PaymentStorageValues.micros(now);
        if (time.isBefore(updatedAt)) throw new IllegalArgumentException("판단 시각이 역전되었습니다");
        Long actionId = null;
        if (resolution == PaymentCancellationResolution.LEGACY_VOID) {
            if (payment.getStatus() != PaymentStatus.VOIDED) throw new IllegalStateException("기존 승인 취소가 확인되지 않았습니다");
            if (existingVoid != null) {
                if (!paymentId.equals(existingVoid.getPaymentId())
                        || existingVoid.getActionType() != PaymentActionType.VOID
                        || existingVoid.getStatus() != PaymentActionStatus.SUCCEEDED)
                    throw new IllegalArgumentException("기존 직원 승인 취소 근거가 일치하지 않습니다");
                actionId = PaymentStorageValues.positive(existingVoid.getActionId());
            }
        } else if (existingVoid != null) {
            throw new IllegalArgumentException("기존 승인 취소 외의 판단에는 직원 근거를 저장하지 않습니다");
        }
        String code = switch (resolution) {
            case DEFERRED -> "PAYMENT_IN_PROGRESS";
            case REVIEW_REQUIRED -> "PAYMENT_REVIEW_REQUIRED";
            case REJECTED -> "PAYMENT_NOT_CANCELABLE";
            default -> null;
        };
        if (this.resolution == resolution && Objects.equals(existingVoidActionId, actionId)) return;
        this.resolution = resolution;
        this.responseCode = code;
        this.existingVoidActionId = actionId;
        this.updatedAt = time;
    }

    private static String requireReason(String reason) {
        if (reason == null
                || reason.isEmpty()
                || reason.codePointCount(0, reason.length()) > 200
                || isSpace(reason.codePointAt(0))
                || isSpace(reason.codePointBefore(reason.length())))
            throw new IllegalArgumentException("취소 사유는 양끝 공백 없는 1~200자여야 합니다");
        return reason;
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
