package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationRequestConflictException;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FdsEvaluationTransactionService {

    private static final long HIGH_AMOUNT_THRESHOLD = 150_000L;
    private static final int HIGH_AMOUNT_SCORE = 35;
    private static final Duration REPEAT_WINDOW = Duration.ofMinutes(10);
    private static final int REPEAT_COUNT_THRESHOLD = 3;
    private static final int REPEAT_SCORE = 25;
    private static final int REVIEW_MIN_SCORE = 50;
    private static final int DENY_MIN_SCORE = 80;

    private final FdsEvaluationRepository evaluationRepository;

    FdsEvaluationTransactionService(FdsEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    @Transactional
    public FdsEvaluationResponse evaluate(FdsEvaluationRequest request) {
        var existing = evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId());
        if (existing.isPresent()) {
            return responseOf(requireSameRequest(existing.get(), request));
        }

        var highAmountMatched = request.amount() >= HIGH_AMOUNT_THRESHOLD;
        var windowStart = request.paymentRequestedAt().minus(REPEAT_WINDOW);
        var priorPaymentIds = evaluationRepository
                .findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        request.merchantId(), request.customerRef(), windowStart, request.paymentRequestedAt())
                .stream()
                .map(FdsEvaluation::getPaymentId)
                .collect(java.util.stream.Collectors.toSet());
        priorPaymentIds.add(request.paymentId());
        var observedAttemptCount = priorPaymentIds.size();
        var repeatedPaymentMatched = observedAttemptCount >= REPEAT_COUNT_THRESHOLD;
        var score = score(highAmountMatched, repeatedPaymentMatched);
        var result = resultFor(score);
        var evaluatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var evaluation = FdsEvaluation.completed(request, result, score, evaluatedAt);
        evaluation.addRuleHit(
                "HIGH_AMOUNT",
                highAmountMatched ? FdsRuleHitOutcome.MATCH : FdsRuleHitOutcome.NO_MATCH,
                highAmountMatched ? HIGH_AMOUNT_SCORE : 0,
                "고액 결제",
                "{\"amountGte\":%d,\"score\":%d}".formatted(HIGH_AMOUNT_THRESHOLD, HIGH_AMOUNT_SCORE),
                "{\"amount\":%d}".formatted(request.amount()),
                null,
                null);
        evaluation.addRuleHit(
                "RAPID_REPEAT_PAYMENT",
                repeatedPaymentMatched ? FdsRuleHitOutcome.MATCH : FdsRuleHitOutcome.NO_MATCH,
                repeatedPaymentMatched ? REPEAT_SCORE : 0,
                "단시간 반복 결제",
                "{\"windowMinutes\":%d,\"attemptCountGte\":%d,\"score\":%d}"
                        .formatted(REPEAT_WINDOW.toMinutes(), REPEAT_COUNT_THRESHOLD, REPEAT_SCORE),
                "{\"attemptCount\":%d}".formatted(observedAttemptCount),
                windowStart,
                request.paymentRequestedAt());
        return responseOf(evaluationRepository.saveAndFlush(evaluation));
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

    private int score(boolean highAmountMatched, boolean repeatedPaymentMatched) {
        return (highAmountMatched ? HIGH_AMOUNT_SCORE : 0) + (repeatedPaymentMatched ? REPEAT_SCORE : 0);
    }

    private FdsResult resultFor(int score) {
        if (score >= DENY_MIN_SCORE) {
            return FdsResult.DENY;
        }
        if (score >= REVIEW_MIN_SCORE) {
            return FdsResult.REVIEW;
        }
        return FdsResult.ALLOW;
    }
}
