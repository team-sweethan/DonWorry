package com.donworry.payment.entity;

import com.donworry.fds.api.FdsEvaluationFailureAnalyzed;
import com.donworry.fds.api.FdsFailureDisposition;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_fds_recoveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentFdsRecovery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recoveryId;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private UUID originalEvaluationRequestId;

    @Column(nullable = false)
    private Long originalEvaluationId;

    @Column(nullable = false, length = 100)
    private String originalErrorCode;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant originalEvaluatedAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant evaluationRequestedAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant evaluationRespondedAt;

    @Column(nullable = false)
    private UUID failureReportId;

    @Column(nullable = true)
    private Long analysisId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = true, length = 40)
    private FdsFailureDisposition disposition;

    @Column(nullable = true, length = 100)
    private String reasonCode;

    @Column(nullable = true, columnDefinition = "DATETIME(6)")
    private Instant analyzedAt;

    @Column(nullable = true)
    private UUID newEvaluationRequestId;

    @Column(nullable = true)
    private UUID retryId;

    @Column(nullable = true)
    private Long requestedBy;

    @Column(nullable = true, columnDefinition = "DATETIME(6)")
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentFdsRecoveryStatus status;

    @Column(nullable = true, columnDefinition = "DATETIME(6)")
    private Instant completedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentFdsRecovery(
            Long paymentId,
            PaymentEvaluationSnapshot original,
            UUID failureReportId,
            Instant requestedAt,
            Instant respondedAt) {
        Objects.requireNonNull(original, "original");
        if (!"ERROR".equals(original.result())) throw new IllegalArgumentException("ERROR 평가만 복구할 수 있습니다");
        this.paymentId = PaymentStorageValues.positive(paymentId);
        this.originalEvaluationRequestId = Objects.requireNonNull(original.requestId());
        this.originalEvaluationId = PaymentStorageValues.positive(original.evaluationId());
        this.originalErrorCode = PaymentStorageValues.reference(original.errorCode());
        this.originalEvaluatedAt = PaymentStorageValues.micros(original.evaluatedAt());
        this.evaluationRequestedAt = PaymentStorageValues.micros(requestedAt);
        this.evaluationRespondedAt = PaymentStorageValues.micros(respondedAt);
        this.failureReportId = Objects.requireNonNull(failureReportId, "failureReportId");
        this.status = PaymentFdsRecoveryStatus.WAITING_ANALYSIS;
    }

    public static PaymentFdsRecovery waitingAnalysis(
            Long paymentId,
            PaymentEvaluationSnapshot original,
            UUID failureReportId,
            Instant requestedAt,
            Instant respondedAt) {
        return PaymentFdsRecovery.builder()
                .paymentId(paymentId)
                .original(original)
                .failureReportId(failureReportId)
                .requestedAt(requestedAt)
                .respondedAt(respondedAt)
                .build();
    }

    public void recordAnalysis(FdsEvaluationFailureAnalyzed event, Instant receivedAt) {
        Objects.requireNonNull(event, "event");
        if (!failureReportId.equals(event.failureReportId())
                || !paymentId.equals(event.paymentId())
                || !originalEvaluationRequestId.equals(event.originalEvaluationRequestId())
                || !originalEvaluationRequestId.equals(event.evaluationRequestId()))
            throw new IllegalArgumentException("분석 이벤트의 원본 식별자가 일치하지 않습니다");
        Long id = PaymentStorageValues.positive(event.analysisId());
        var result = Objects.requireNonNull(event.disposition(), "disposition");
        String reason = PaymentStorageValues.reference(event.reasonCode());
        Instant time = PaymentStorageValues.micros(event.analyzedAt());
        if (analysisId != null) {
            if (analysisId.equals(id) && disposition == result && reasonCode.equals(reason) && analyzedAt.equals(time))
                return;
            throw new IllegalStateException("저장된 분석 결과와 충돌합니다");
        }
        if (status != PaymentFdsRecoveryStatus.WAITING_ANALYSIS) throw new IllegalStateException("분석 수신 가능한 상태가 아닙니다");
        Instant completed =
                result == FdsFailureDisposition.REEVALUATION_AVAILABLE ? null : PaymentStorageValues.micros(receivedAt);
        analysisId = id;
        disposition = result;
        reasonCode = reason;
        analyzedAt = time;
        completedAt = completed;
        status = completed == null ? PaymentFdsRecoveryStatus.READY : PaymentFdsRecoveryStatus.MANUAL_REVIEW;
    }
}
