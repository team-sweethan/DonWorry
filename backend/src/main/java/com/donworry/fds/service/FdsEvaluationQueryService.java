package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationDetail;
import com.donworry.fds.api.FdsEvaluationDetail.FdsRuleHitDetail;
import com.donworry.fds.api.FdsEvaluationPage;
import com.donworry.fds.api.FdsEvaluationSummary;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.util.EnumMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FdsEvaluationQueryService {

    private final FdsEvaluationRepository evaluationRepository;

    public FdsEvaluationQueryService(FdsEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    public FdsEvaluationPage list(FdsResult result, boolean attentionOnly, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "evaluatedAt"));
        var evaluations = attentionOnly
                ? evaluationRepository.findByResultNotOrderByEvaluatedAtDesc(FdsResult.ALLOW, pageable)
                : result == null
                        ? evaluationRepository.findAllByOrderByEvaluatedAtDesc(pageable)
                        : evaluationRepository.findByResultOrderByEvaluatedAtDesc(result, pageable);
        var resultCounts = new EnumMap<FdsResult, Long>(FdsResult.class);
        for (var candidate : FdsResult.values()) {
            resultCounts.put(candidate, evaluationRepository.countByResult(candidate));
        }
        return new FdsEvaluationPage(
                evaluations.map(this::summary).toList(),
                evaluations.getNumber(),
                evaluations.getSize(),
                evaluations.getTotalElements(),
                evaluations.getTotalPages(),
                resultCounts);
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
