package com.donworry.fds.entity;

import com.donworry.fds.api.FdsEvaluationFailureReport;
import com.donworry.fds.api.FdsFailureDisposition;
import com.donworry.fds.api.FdsResult;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "fds_failure_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsFailureAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long analysisId;

    @Column(name = "failure_report_id", nullable = false, unique = true)
    private UUID failureReportId;

    @Column(name = "original_evaluation_request_id", nullable = false)
    private UUID originalEvaluationRequestId;

    @Column(name = "evaluation_request_id", nullable = false)
    private UUID evaluationRequestId;

    @Column(name = "evaluation_id", nullable = false)
    private Long evaluationId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "customer_ref", nullable = false, length = 100)
    private String customerRef;

    @Column(name = "requested_amount", nullable = false)
    private Long requestedAmount;

    @Column(name = "payment_requested_at", nullable = false)
    private Instant paymentRequestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_result", length = 20)
    private FdsResult evaluationResult;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "sanitized_error_message", length = 500)
    private String sanitizedErrorMessage;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "payment_status", nullable = false, length = 30)
    private String paymentStatus;

    @Column(name = "processing_stage", nullable = false, length = 30)
    private String processingStage;

    @Column(name = "payment_request_id", nullable = false)
    private Long paymentRequestId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "order_ref", length = 100)
    private String orderRef;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "evaluation_attempt_no", nullable = false)
    private Integer evaluationAttemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private FdsFailureDisposition disposition;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private FdsFailureAnalysis(
            FdsEvaluationFailureReport report,
            FdsFailureDisposition disposition,
            String reasonCode,
            Instant analyzedAt) {
        this.failureReportId = report.failureReportId();
        this.originalEvaluationRequestId = report.originalEvaluationRequestId();
        this.evaluationRequestId = report.evaluationRequestId();
        this.evaluationId = report.evaluationId();
        this.paymentId = report.paymentId();
        this.merchantId = report.merchantId();
        this.customerRef = report.customerRef();
        this.requestedAmount = report.amount();
        this.paymentRequestedAt = micros(report.paymentRequestedAt());
        this.evaluationResult = report.evaluationResult();
        this.errorCode = report.errorCode();
        this.sanitizedErrorMessage = report.sanitizedErrorMessage();
        this.requestedAt = micros(report.requestedAt());
        this.respondedAt = micros(report.respondedAt());
        this.paymentStatus = report.paymentStatus();
        this.processingStage = report.processingStage();
        this.paymentRequestId = report.requestId();
        this.idempotencyKey = report.idempotencyKey();
        this.orderRef = report.orderRef();
        this.correlationId = report.correlationId();
        this.evaluationAttemptNo = report.evaluationAttemptNo();
        this.disposition = disposition;
        this.reasonCode = reasonCode;
        this.analyzedAt = micros(analyzedAt);
    }

    public static FdsFailureAnalysis analyzed(
            FdsEvaluationFailureReport report,
            FdsFailureDisposition disposition,
            String reasonCode,
            Instant analyzedAt) {
        return FdsFailureAnalysis.builder()
                .report(report)
                .disposition(disposition)
                .reasonCode(reasonCode)
                .analyzedAt(analyzedAt)
                .build();
    }

    private static Instant micros(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}
