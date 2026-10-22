package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationDetail;
import com.donworry.fds.api.FdsEvaluationDetail.FdsRuleHitDetail;
import com.donworry.fds.api.FdsEvaluationSummary;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FdsEvaluationQueryService {

    private final FdsEvaluationRepository evaluationRepository;

    public FdsEvaluationQueryService(FdsEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    public List<FdsEvaluationSummary> list(FdsResult result) {
        return evaluationRepository.findAllByOrderByEvaluatedAtDesc().stream()
                .filter(evaluation -> result == null || evaluation.getResult() == result)
                .map(this::summary)
                .toList();
    }

    public FdsEvaluationDetail detail(Long evaluationId) {
        var evaluation = evaluationRepository.findById(evaluationId).orElseThrow(FdsEvaluationNotFoundException::new);
        var ruleHits = evaluation.getRuleHits().stream()
                .map(hit -> new FdsRuleHitDetail(
                        hit.getHitId(),
                        hit.getRuleCode(),
                        hit.getRuleNameSnapshot(),
                        hit.getOutcome(),
                        hit.getAppliedScore(),
                        hit.getObservedValue(),
                        hit.getThresholdSnapshot()))
                .toList();
        var history = evaluationRepository.findByPaymentIdOrderByEvaluatedAtDesc(evaluation.getPaymentId()).stream()
                .map(this::summary)
                .toList();
        return new FdsEvaluationDetail(summary(evaluation), ruleHits, history);
    }

    private FdsEvaluationSummary summary(FdsEvaluation evaluation) {
        return new FdsEvaluationSummary(
                evaluation.getEvaluationId(),
                evaluation.getPaymentId(),
                evaluation.getCustomerRef(),
                evaluation.getRiskScore(),
                evaluation.getResult(),
                evaluation.getEvaluatedAt(),
                evaluation.getErrorCode(),
                evaluation.getPaymentRequestedAt());
    }
}
