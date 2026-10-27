package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.*;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.entity.FdsFailureAnalysis;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.fds.repository.FdsFailureAnalysisRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class FdsFailureAnalysisServiceTests {

    @Mock
    FdsFailureAnalysisRepository analyses;

    @Mock
    FdsEvaluationRepository evaluations;

    @Mock
    ApplicationEventPublisher events;

    @Captor
    ArgumentCaptor<FdsEvaluationFailureAnalyzed> eventCaptor;

    @Test
    // 일시 오류로 저장된 평가만 재탐지 가능으로 P5에 알린다.
    void report_whenStoredTransientError_publishesReevaluationAvailable() {
        var report = report();
        var evaluation = FdsEvaluation.failed(
                request(report), "TRANSIENT_MODEL_UNAVAILABLE", Instant.parse("2026-10-27T01:00:01Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 99L);
        when(analyses.findByFailureReportId(report.failureReportId())).thenReturn(Optional.empty());
        when(evaluations.findByEvaluationRequestId(report.evaluationRequestId()))
                .thenReturn(Optional.of(evaluation));
        when(analyses.save(any(FdsFailureAnalysis.class))).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, FdsFailureAnalysis.class);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "analysisId", 7L);
            return saved;
        });

        new FdsFailureAnalysisService(analyses, evaluations, events).report(report);

        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().disposition()).isEqualTo(FdsFailureDisposition.REEVALUATION_AVAILABLE);
        assertThat(eventCaptor.getValue().reasonCode()).isEqualTo("TRANSIENT_ERROR_RECOVERED");
    }

    @Test
    // P2 내부 오류 보고인데 평가 이력이 없으면 ERROR 평가를 임의로 만들지 않는다.
    void report_whenEvaluationIsMissing_publishesNotFound() {
        var report = report();
        when(analyses.findByFailureReportId(report.failureReportId())).thenReturn(Optional.empty());
        when(evaluations.findByEvaluationRequestId(report.evaluationRequestId()))
                .thenReturn(Optional.empty());
        when(analyses.save(any(FdsFailureAnalysis.class))).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, FdsFailureAnalysis.class);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "analysisId", 8L);
            return saved;
        });

        new FdsFailureAnalysisService(analyses, evaluations, events).report(report);

        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().disposition()).isEqualTo(FdsFailureDisposition.EVALUATION_NOT_FOUND);
        verify(evaluations, never()).save(any());
    }

    @Test
    // 같은 실패 보고는 이벤트를 다시 발행하지 않는다.
    void report_whenAlreadyReported_doesNotPublishAgain() {
        var report = report();
        when(analyses.findByFailureReportId(report.failureReportId()))
                .thenReturn(Optional.of(mock(FdsFailureAnalysis.class)));

        new FdsFailureAnalysisService(analyses, evaluations, events).report(report);

        verifyNoInteractions(events);
    }

    private FdsEvaluationFailureReport report() {
        var now = Instant.parse("2026-10-27T01:00:00Z");
        return new FdsEvaluationFailureReport(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                99L,
                10L,
                1L,
                "customer",
                1000L,
                now.minusSeconds(10),
                FdsResult.ERROR,
                "TRANSIENT_MODEL_UNAVAILABLE",
                "internal model failure",
                now.minusSeconds(5),
                now,
                "PENDING",
                "NEEDS_REVIEW",
                30L,
                UUID.randomUUID(),
                "order",
                null,
                1);
    }

    private FdsEvaluationRequest request(FdsEvaluationFailureReport report) {
        return new FdsEvaluationRequest(
                report.evaluationRequestId(),
                report.paymentId(),
                report.merchantId(),
                report.customerRef(),
                report.amount(),
                report.paymentRequestedAt());
    }
}
