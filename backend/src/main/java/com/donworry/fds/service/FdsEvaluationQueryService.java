package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationDetail;
import com.donworry.fds.api.FdsEvaluationDetail.FdsRuleHitDetail;
import com.donworry.fds.api.FdsEvaluationPage;
import com.donworry.fds.api.FdsEvaluationSummary;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.util.EnumMap;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FdsEvaluationQueryService {

    private final FdsEvaluationRepository evaluationRepository;

    public FdsEvaluationQueryService(FdsEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    public FdsEvaluationPage list(FdsResult result, boolean attentionOnly, String search, int page, int size) {
        var pageable =
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("evaluatedAt"), Sort.Order.desc("evaluationId")));
        var evaluations = evaluationRepository.findAll(filters(result, attentionOnly, search), pageable);
        var resultCounts = new EnumMap<FdsResult, Long>(FdsResult.class);
        for (var candidate : FdsResult.values()) {
            resultCounts.put(candidate, evaluationRepository.countByResult(candidate));
        }
        return FdsEvaluationPage.builder()
                .content(evaluations.map(this::summary).toList())
                .page(evaluations.getNumber())
                .size(evaluations.getSize())
                .totalElements(evaluations.getTotalElements())
                .totalPages(evaluations.getTotalPages())
                .resultCounts(resultCounts)
                .build();
    }

    private Specification<FdsEvaluation> filters(FdsResult result, boolean attentionOnly, String search) {
        return (root, query, criteriaBuilder) -> {
            var predicate = criteriaBuilder.conjunction();
            if (attentionOnly) {
                predicate =
                        criteriaBuilder.and(predicate, criteriaBuilder.notEqual(root.get("result"), FdsResult.ALLOW));
            } else if (result != null) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(root.get("result"), result));
            }
            if (search == null || search.isBlank()) {
                return predicate;
            }
            var normalized = search.trim().toLowerCase(Locale.ROOT);
            var customerRef = criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("customerRef")), "%" + escapeLike(normalized) + "%", '\\');
            var number = parseLong(normalized);
            if (number == null) {
                return criteriaBuilder.and(predicate, customerRef);
            }
            return criteriaBuilder.and(
                    predicate,
                    criteriaBuilder.or(
                            customerRef,
                            criteriaBuilder.equal(root.get("evaluationId"), number),
                            criteriaBuilder.equal(root.get("paymentId"), number)));
        };
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
