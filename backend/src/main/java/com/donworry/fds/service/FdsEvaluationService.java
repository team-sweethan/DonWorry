package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class FdsEvaluationService implements FdsEvaluator {

    private final FdsEvaluationRepository evaluationRepository;
    private final FdsEvaluationTransactionService transactionService;

    public FdsEvaluationService(
            FdsEvaluationRepository evaluationRepository, FdsEvaluationTransactionService transactionService) {
        this.evaluationRepository = evaluationRepository;
        this.transactionService = transactionService;
    }

    @Override
    public FdsEvaluationResponse evaluate(FdsEvaluationRequest request) {
        try {
            return transactionService.evaluate(request);
        } catch (DataIntegrityViolationException exception) {
            return evaluationRepository
                    .findByEvaluationRequestId(request.evaluationRequestId())
                    .map(this::responseOf)
                    .orElseThrow(() -> exception);
        }
    }

    private FdsEvaluationResponse responseOf(FdsEvaluation evaluation) {
        return new FdsEvaluationResponse(
                evaluation.getEvaluationRequestId(),
                evaluation.getEvaluationId(),
                evaluation.getResult(),
                evaluation.getRiskScore(),
                evaluation.getEvaluatedAt(),
                evaluation.getErrorCode());
    }
}
