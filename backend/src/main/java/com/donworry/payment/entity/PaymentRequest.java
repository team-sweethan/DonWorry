package com.donworry.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long requestId;

    @Column(nullable = false)
    private Long paymentOrderId;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long attemptNo;

    @Column(nullable = false)
    private UUID idempotencyKey;

    @Column(nullable = false)
    private UUID evaluationRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProcessingStage processingStage;

    private Long evaluationId;

    @Column(length = 20)
    private String evaluationResult;

    private Integer evaluationRiskScore;

    @Column(length = 100)
    private String evaluationErrorCode;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant evaluatedAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentRequest(
            Long paymentOrderId,
            Long merchantId,
            Long paymentId,
            Long attemptNo,
            UUID idempotencyKey,
            UUID evaluationRequestId,
            Instant createdAt) {
        this.paymentOrderId = PaymentStorageValues.positive(paymentOrderId);
        this.merchantId = PaymentStorageValues.positive(merchantId);
        this.paymentId = PaymentStorageValues.positive(paymentId);
        this.attemptNo = PaymentStorageValues.positive(attemptNo);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.evaluationRequestId = Objects.requireNonNull(evaluationRequestId, "evaluationRequestId");
        this.createdAt = PaymentStorageValues.micros(createdAt);
        this.processingStage = PaymentProcessingStage.RECEIVED;
    }

    public static PaymentRequest received(
            PaymentOrder order, Payment payment, long attemptNo, UUID key, UUID evaluationRequestId) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(payment, "payment");
        payment.requireUnapprovedPending();
        if (!order.getMerchantId().equals(payment.getMerchantId())
                || !order.matches(
                        payment.getOrderRef(), payment.getCustomerRef(), payment.getAmount(), order.getOrderCreatedAt())
                || order.getLastAttemptNo() != attemptNo) throw new IllegalArgumentException("주문과 결제 시도가 일치하지 않습니다");
        return PaymentRequest.builder()
                .paymentOrderId(order.getPaymentOrderId())
                .merchantId(order.getMerchantId())
                .paymentId(payment.getPaymentId())
                .attemptNo(attemptNo)
                .idempotencyKey(key)
                .evaluationRequestId(evaluationRequestId)
                .createdAt(payment.getCreatedAt())
                .build();
    }

    public Optional<PaymentEvaluationSnapshot> evaluationSnapshot() {
        if (evaluationId == null) return Optional.empty();
        return Optional.of(new PaymentEvaluationSnapshot(
                evaluationRequestId,
                evaluationId,
                evaluationResult,
                evaluationRiskScore,
                evaluationErrorCode,
                evaluatedAt));
    }

    public void recordEvaluation(PaymentEvaluationSnapshot result) {
        Objects.requireNonNull(result, "result");
        if (!evaluationRequestId.equals(result.requestId())) throw new IllegalArgumentException("평가 요청 ID 불일치");
        if (processingStage != PaymentProcessingStage.RECEIVED) {
            if (evaluationSnapshot().orElseThrow().equals(result)) return;
            throw new IllegalStateException("저장된 평가 결과는 변경할 수 없습니다");
        }
        evaluationId = result.evaluationId();
        evaluationResult = result.result();
        evaluationRiskScore = result.riskScore();
        evaluationErrorCode = result.errorCode();
        evaluatedAt = result.evaluatedAt();
        processingStage = result.result().equals("ERROR")
                ? PaymentProcessingStage.NEEDS_REVIEW
                : PaymentProcessingStage.EVALUATED;
    }

    public void completeAuthorization() {
        if (processingStage == PaymentProcessingStage.AUTHORIZED) return;
        if (processingStage != PaymentProcessingStage.EVALUATED) throw new IllegalStateException("승인 가능한 평가가 없습니다");
        processingStage = PaymentProcessingStage.AUTHORIZED;
    }
}
