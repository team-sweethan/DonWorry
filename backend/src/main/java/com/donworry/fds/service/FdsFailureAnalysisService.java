package com.donworry.fds.service;

import com.donworry.fds.api.*;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.entity.FdsFailureAnalysis;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.fds.repository.FdsFailureAnalysisRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FdsFailureAnalysisService implements FdsFailureReporter {

    private final FdsFailureAnalysisRepository analyses;
    private final FdsEvaluationRepository evaluations;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public void report(FdsEvaluationFailureReport report) {
        if (analyses.findByFailureReportId(report.failureReportId()).isPresent()) return;

        var evaluation = evaluations
                .findByEvaluationRequestId(report.evaluationRequestId())
                .orElse(null);
        validateReportLineage(report, evaluation);
        var outcome = analyze(report, evaluation);
        var analysis = analyses.save(
                FdsFailureAnalysis.analyzed(report, outcome.disposition(), outcome.reasonCode(), Instant.now()));
        events.publishEvent(new FdsEvaluationFailureAnalyzed(
                analysis.getAnalysisId(),
                analysis.getFailureReportId(),
                analysis.getOriginalEvaluationRequestId(),
                analysis.getEvaluationRequestId(),
                analysis.getPaymentId(),
                analysis.getDisposition(),
                analysis.getReasonCode(),
                analysis.getAnalyzedAt()));
    }

    private AnalysisOutcome analyze(FdsEvaluationFailureReport report, FdsEvaluation evaluation) {
        if (evaluation == null) {
            return new AnalysisOutcome(FdsFailureDisposition.EVALUATION_NOT_FOUND, "EVALUATION_NOT_FOUND");
        }
        if (!evaluation.hasSameRequestSnapshot(new FdsEvaluationRequest(
                report.evaluationRequestId(),
                report.paymentId(),
                report.merchantId(),
                report.customerRef(),
                report.amount(),
                report.paymentRequestedAt(),
                report.deviceId(),
                report.cardToken(),
                report.cardDisplayLabel(),
                report.ipCountryCode(),
                report.ipAddress(),
                report.buyerEmail()))) {
            return new AnalysisOutcome(FdsFailureDisposition.PAYMENT_FOLLOW_UP_REQUIRED, "EVALUATION_INPUT_MISMATCH");
        }
        if (evaluation.getResult() != FdsResult.ERROR) {
            return new AnalysisOutcome(FdsFailureDisposition.EXISTING_RESULT_AVAILABLE, "EVALUATION_ALREADY_COMPLETED");
        }
        if (evaluation.getErrorCode() != null && evaluation.getErrorCode().startsWith("TRANSIENT_")) {
            return new AnalysisOutcome(FdsFailureDisposition.REEVALUATION_AVAILABLE, "TRANSIENT_ERROR_RECOVERED");
        }
        return new AnalysisOutcome(FdsFailureDisposition.MANUAL_REVIEW_REQUIRED, "ERROR_REQUIRES_MANUAL_REVIEW");
    }

    private void validateReportLineage(FdsEvaluationFailureReport report, FdsEvaluation evaluation) {
        if (evaluation == null) return;
        if (!evaluation.getEvaluationId().equals(report.evaluationId())) {
            throw new IllegalArgumentException("평가 ID가 평가 요청 UUID와 일치하지 않습니다");
        }
        if (report.originalEvaluationRequestId().equals(report.evaluationRequestId())) return;
        if (!report.originalEvaluationRequestId().equals(evaluation.getOriginalEvaluationRequestId())) {
            throw new IllegalArgumentException("원본 평가 UUID가 실제 재탐지 계보와 일치하지 않습니다");
        }
    }

    private record AnalysisOutcome(FdsFailureDisposition disposition, String reasonCode) {}
}
