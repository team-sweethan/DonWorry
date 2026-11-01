package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.*;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.fds.repository.FdsFailureAnalysisRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FdsReevaluationServiceTests {

    @Mock
    FdsEvaluationRepository evaluations;

    @Mock
    FdsFailureAnalysisRepository analyses;

    @Mock
    FdsEvaluator evaluator;

    @Test
    // 재탐지 가능 분석 결과가 있는 ERROR 평가는 새 UUID로 다시 평가한다.
    void reevaluate_whenAnalysisAllows_callsEvaluatorWithNewRequestId() {
        var request = request();
        var original = FdsEvaluation.failed(
                originalRequest(request), "TRANSIENT_MODEL_UNAVAILABLE", Instant.parse("2026-10-27T01:00:01Z"));
        var reevaluated = FdsEvaluation.completed(
                request.evaluationRequest(), FdsResult.ALLOW, 0, Instant.parse("2026-10-27T01:00:02Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(reevaluated, "evaluationId", 101L);
        when(evaluations.findByReevaluationRetryId(request.retryId())).thenReturn(Optional.empty());
        when(evaluations.findByEvaluationRequestIdForUpdate(request.originalEvaluationRequestId()))
                .thenReturn(Optional.of(original));
        when(evaluations.findByEvaluationRequestId(request.newEvaluationRequestId()))
                .thenReturn(Optional.empty());
        when(analyses.existsByOriginalEvaluationRequestIdAndDisposition(
                        request.originalEvaluationRequestId(), FdsFailureDisposition.REEVALUATION_AVAILABLE))
                .thenReturn(true);
        when(evaluator.evaluateHistoricalSnapshot(request.evaluationRequest()))
                .thenReturn(new FdsEvaluationResponse(
                        request.newEvaluationRequestId(),
                        101L,
                        FdsResult.ALLOW,
                        0,
                        reevaluated.getEvaluatedAt(),
                        null));
        when(evaluations.findById(101L)).thenReturn(Optional.of(reevaluated));

        var response = new FdsReevaluationService(evaluations, analyses, evaluator).reevaluate(request);

        assertThat(response.result()).isEqualTo(FdsResult.ALLOW);
        assertThat(reevaluated.getOriginalEvaluationRequestId()).isEqualTo(request.originalEvaluationRequestId());
        assertThat(reevaluated.getReevaluationRetryId()).isEqualTo(request.retryId());
    }

    @Test
    // 마이그레이션 이전 ERROR 평가는 누락된 위험 신호를 원본 그대로 사용해 재탐지한다.
    void reevaluate_whenHistoricalRiskSignalsAreMissing_callsHistoricalEvaluation() {
        var request = FdsReevaluationRequest.builder()
                .originalEvaluationRequestId(UUID.randomUUID())
                .newEvaluationRequestId(UUID.randomUUID())
                .paymentId(10L)
                .merchantId(1L)
                .customerRef("legacy-customer")
                .amount(10_000L)
                .paymentRequestedAt(Instant.parse("2026-10-27T01:00:00Z"))
                .retryId(UUID.randomUUID())
                .build();
        var original = FdsEvaluation.failed(
                originalRequest(request), "TRANSIENT_MODEL_UNAVAILABLE", Instant.parse("2026-10-27T01:00:01Z"));
        var reevaluated = FdsEvaluation.completed(
                request.evaluationRequest(), FdsResult.ALLOW, 0, Instant.parse("2026-10-27T01:00:02Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(reevaluated, "evaluationId", 102L);
        when(evaluations.findByReevaluationRetryId(request.retryId())).thenReturn(Optional.empty());
        when(evaluations.findByEvaluationRequestIdForUpdate(request.originalEvaluationRequestId()))
                .thenReturn(Optional.of(original));
        when(evaluations.findByEvaluationRequestId(request.newEvaluationRequestId()))
                .thenReturn(Optional.empty());
        when(analyses.existsByOriginalEvaluationRequestIdAndDisposition(
                        request.originalEvaluationRequestId(), FdsFailureDisposition.REEVALUATION_AVAILABLE))
                .thenReturn(true);
        when(evaluator.evaluateHistoricalSnapshot(request.evaluationRequest()))
                .thenReturn(new FdsEvaluationResponse(
                        request.newEvaluationRequestId(),
                        102L,
                        FdsResult.ALLOW,
                        0,
                        reevaluated.getEvaluatedAt(),
                        null));
        when(evaluations.findById(102L)).thenReturn(Optional.of(reevaluated));

        var response = new FdsReevaluationService(evaluations, analyses, evaluator).reevaluate(request);

        assertThat(response.result()).isEqualTo(FdsResult.ALLOW);
        verify(evaluator).evaluateHistoricalSnapshot(request.evaluationRequest());
    }

    @Test
    // 분석 결과가 재탐지 가능이 아니면 새 평가를 실행하지 않는다.
    void reevaluate_whenAnalysisDoesNotAllow_throwsException() {
        var request = request();
        var original =
                FdsEvaluation.failed(originalRequest(request), "FDS_RULE_ERROR", Instant.parse("2026-10-27T01:00:01Z"));
        when(evaluations.findByReevaluationRetryId(request.retryId())).thenReturn(Optional.empty());
        when(evaluations.findByEvaluationRequestIdForUpdate(request.originalEvaluationRequestId()))
                .thenReturn(Optional.of(original));
        when(analyses.existsByOriginalEvaluationRequestIdAndDisposition(any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> new FdsReevaluationService(evaluations, analyses, evaluator).reevaluate(request))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(evaluator);
    }

    @Test
    // 이미 사용된 새 평가 UUID는 과거 평가를 재탐지 결과로 재사용하지 않는다.
    void reevaluate_whenNewEvaluationRequestIdAlreadyExists_throwsException() {
        var request = request();
        var original = FdsEvaluation.failed(
                originalRequest(request), "TRANSIENT_MODEL_UNAVAILABLE", Instant.parse("2026-10-27T01:00:01Z"));
        when(evaluations.findByReevaluationRetryId(request.retryId())).thenReturn(Optional.empty());
        when(evaluations.findByEvaluationRequestIdForUpdate(request.originalEvaluationRequestId()))
                .thenReturn(Optional.of(original));
        when(analyses.existsByOriginalEvaluationRequestIdAndDisposition(
                        request.originalEvaluationRequestId(), FdsFailureDisposition.REEVALUATION_AVAILABLE))
                .thenReturn(true);
        when(evaluations.findByEvaluationRequestId(request.newEvaluationRequestId()))
                .thenReturn(Optional.of(mock(FdsEvaluation.class)));

        assertThatThrownBy(() -> new FdsReevaluationService(evaluations, analyses, evaluator).reevaluate(request))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(evaluator);
    }

    @Test
    // 같은 retryId의 재전송도 원래 재탐지 입력과 다르면 기존 결과를 반환하지 않는다.
    void reevaluate_whenRetriedRequestInputDiffers_throwsException() {
        var request = request();
        var existing = FdsEvaluation.completed(
                request.evaluationRequest(), FdsResult.ALLOW, 0, Instant.parse("2026-10-27T01:00:01Z"));
        existing.recordReevaluation(request.originalEvaluationRequestId(), request.retryId());
        var changedRequest = new FdsReevaluationRequest(
                request.originalEvaluationRequestId(),
                request.newEvaluationRequestId(),
                request.paymentId() + 1,
                request.merchantId(),
                request.customerRef(),
                request.amount(),
                request.paymentRequestedAt(),
                request.deviceId(),
                request.cardToken(),
                request.cardDisplayLabel(),
                request.ipCountryCode(),
                request.ipAddress(),
                request.buyerEmail(),
                request.retryId());
        when(evaluations.findByReevaluationRetryId(request.retryId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(
                        () -> new FdsReevaluationService(evaluations, analyses, evaluator).reevaluate(changedRequest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FdsReevaluationRequest request() {
        return new FdsReevaluationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                10L,
                1L,
                "customer",
                1000L,
                Instant.parse("2026-10-27T01:00:00Z"),
                "device-1",
                "token-1",
                "VISA •••• 1234",
                "kr",
                "203.0.113.10",
                "hong@example.test",
                UUID.randomUUID());
    }

    private FdsEvaluationRequest originalRequest(FdsReevaluationRequest request) {
        return new FdsEvaluationRequest(
                request.originalEvaluationRequestId(),
                request.paymentId(),
                request.merchantId(),
                request.customerRef(),
                request.amount(),
                request.paymentRequestedAt(),
                request.deviceId(),
                request.cardToken(),
                request.cardDisplayLabel(),
                request.ipCountryCode(),
                request.ipAddress(),
                request.buyerEmail());
    }
}
