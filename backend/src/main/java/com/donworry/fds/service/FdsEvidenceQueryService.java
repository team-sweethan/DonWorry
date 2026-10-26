package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvidenceReader;
import com.donworry.fds.api.FdsPaymentEvidence;
import com.donworry.fds.api.FdsPaymentEvidence.MatchedRule;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FdsEvidenceQueryService implements FdsEvidenceReader {

    private final FdsEvaluationRepository evaluationRepository;

    public FdsEvidenceQueryService(FdsEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    @Override
    public List<FdsPaymentEvidence> findLatestByPaymentIds(Collection<Long> paymentIds) {
        var distinctPaymentIds = distinctPositivePaymentIds(paymentIds);
        if (distinctPaymentIds.isEmpty()) {
            return List.of();
        }

        Map<Long, List<FdsEvaluation>> evaluationsByPaymentId =
                evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(distinctPaymentIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(FdsEvaluation::getPaymentId));

        return distinctPaymentIds.stream()
                .map(evaluationsByPaymentId::get)
                .filter(Objects::nonNull)
                .map(this::latestUsableOrLatestError)
                .map(this::evidenceOf)
                .toList();
    }

    private LinkedHashSet<Long> distinctPositivePaymentIds(Collection<Long> paymentIds) {
        if (paymentIds == null) {
            throw new IllegalArgumentException("paymentIds must not be null");
        }
        var distinctPaymentIds = new LinkedHashSet<Long>();
        for (Long paymentId : paymentIds) {
            if (paymentId == null || paymentId <= 0) {
                throw new IllegalArgumentException("paymentIds must contain only positive values");
            }
            distinctPaymentIds.add(paymentId);
        }
        return distinctPaymentIds;
    }

    private FdsEvaluation latestUsableOrLatestError(List<FdsEvaluation> evaluations) {
        var latest = Comparator.comparing(FdsEvaluation::getEvaluatedAt)
                .thenComparing(FdsEvaluation::getEvaluationId, Comparator.nullsLast(Comparator.naturalOrder()));
        return evaluations.stream()
                .filter(evaluation -> evaluation.getResult() != FdsResult.ERROR)
                .max(latest)
                .orElseGet(() -> evaluations.stream().max(latest).orElseThrow());
    }

    private FdsPaymentEvidence evidenceOf(FdsEvaluation evaluation) {
        var matchedRules = evaluation.getRuleHits().stream()
                .filter(hit -> hit.getOutcome() == FdsRuleHitOutcome.MATCH)
                .map(hit -> new MatchedRule(hit.getRuleCode(), hit.getRuleNameSnapshot(), hit.getAppliedScore()))
                .toList();
        return new FdsPaymentEvidence(
                evaluation.getPaymentId(),
                evaluation.getEvaluationId(),
                evaluation.getResult(),
                evaluation.getRiskScore(),
                evaluation.getEvaluatedAt(),
                matchedRules);
    }
}
