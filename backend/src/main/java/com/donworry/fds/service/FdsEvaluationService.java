package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationRequestConflictException;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class FdsEvaluationService implements FdsEvaluator {

    private final FdsEvaluationRepository evaluationRepository;
    private final FdsEvaluationTransactionService transactionService;
    private final Optional<FdsDemoErrorService> demoError;

    @Autowired
    public FdsEvaluationService(
            FdsEvaluationRepository evaluationRepository,
            FdsEvaluationTransactionService transactionService,
            Optional<FdsDemoErrorService> demoError) {
        this.evaluationRepository = evaluationRepository;
        this.transactionService = transactionService;
        this.demoError = demoError;
    }

    FdsEvaluationService(
            FdsEvaluationRepository evaluationRepository, FdsEvaluationTransactionService transactionService) {
        this(evaluationRepository, transactionService, Optional.empty());
    }

    @Override
    public FdsEvaluationResponse evaluate(FdsEvaluationRequest request) {
        return evaluateInternal(request);
    }

    @Override
    public FdsEvaluationResponse evaluateHistoricalSnapshot(FdsEvaluationRequest request) {
        return evaluateInternal(request);
    }

    private FdsEvaluationResponse evaluateInternal(FdsEvaluationRequest request) {
        var existing = evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId());
        if (existing.isPresent()) return responseOf(requireSameRequest(existing.get(), request));
        var forced = demoError.flatMap(service -> service.failNext(request));
        if (forced.isPresent()) return forced.get();
        try {
            return transactionService.evaluate(request);
        } catch (DataIntegrityViolationException exception) {
            return evaluationRepository
                    .findByEvaluationRequestId(request.evaluationRequestId())
                    .map(evaluation -> responseOf(requireSameRequest(evaluation, request)))
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

    private FdsEvaluation requireSameRequest(FdsEvaluation evaluation, FdsEvaluationRequest request) {
        if (!evaluation.hasSameRequestSnapshot(request)) {
            throw new FdsEvaluationRequestConflictException();
        }
        return evaluation;
    }
}
