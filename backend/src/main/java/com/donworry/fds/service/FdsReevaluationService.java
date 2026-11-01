package com.donworry.fds.service;

import com.donworry.fds.api.*;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.fds.repository.FdsFailureAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FdsReevaluationService implements FdsReevaluationRequester {

    private final FdsEvaluationRepository evaluations;
    private final FdsFailureAnalysisRepository analyses;
    private final FdsEvaluator evaluator;

    @Override
    @Transactional
    public FdsEvaluationResponse reevaluate(FdsReevaluationRequest request) {
        var retried = evaluations.findByReevaluationRetryId(request.retryId());
        if (retried.isPresent()) return responseOf(retried.get(), request);

        var original = evaluations
                .findByEvaluationRequestIdForUpdate(request.originalEvaluationRequestId())
                .orElseThrow(() -> new IllegalArgumentException("원본 ERROR 평가가 없습니다"));
        if (original.getResult() != FdsResult.ERROR) {
            throw new IllegalStateException("ERROR 평가만 재탐지할 수 있습니다");
        }
        if (!original.hasSameRequestSnapshot(request.evaluationRequest())) {
            throw new IllegalArgumentException("원본 평가와 재탐지 입력이 일치하지 않습니다");
        }
        if (!analyses.existsByOriginalEvaluationRequestIdAndDisposition(
                request.originalEvaluationRequestId(), FdsFailureDisposition.REEVALUATION_AVAILABLE)) {
            throw new IllegalStateException("재탐지 가능 분석 결과가 없습니다");
        }
        if (evaluations
                .findByEvaluationRequestId(request.newEvaluationRequestId())
                .isPresent()) {
            throw new IllegalArgumentException("새 평가 UUID가 이미 사용되었습니다");
        }

        var response = evaluator.evaluateHistoricalSnapshot(request.evaluationRequest());
        var evaluation = evaluations.findById(response.evaluationId()).orElseThrow();
        evaluation.recordReevaluation(request.originalEvaluationRequestId(), request.retryId());
        return response;
    }

    private FdsEvaluationResponse responseOf(FdsEvaluation evaluation, FdsReevaluationRequest request) {
        if (!evaluation.getEvaluationRequestId().equals(request.newEvaluationRequestId())
                || !request.originalEvaluationRequestId().equals(evaluation.getOriginalEvaluationRequestId())
                || !evaluation.hasSameRequestSnapshot(request.evaluationRequest())) {
            throw new IllegalArgumentException("retryId가 다른 재탐지 요청에 사용되었습니다");
        }
        return new FdsEvaluationResponse(
                evaluation.getEvaluationRequestId(),
                evaluation.getEvaluationId(),
                evaluation.getResult(),
                evaluation.getRiskScore(),
                evaluation.getEvaluatedAt(),
                evaluation.getErrorCode());
    }
}
