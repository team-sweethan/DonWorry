package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationRequestConflictException;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
import com.donworry.fds.repository.FdsEvaluationRepository;
import com.donworry.fds.repository.FdsHighRiskCountryRepository;
import com.donworry.fds.repository.FdsRulePolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class FdsEvaluationTransactionService {

    private static final long HIGH_AMOUNT_THRESHOLD = 500_000L;
    private static final int HIGH_AMOUNT_SCORE = 30;
    private static final Duration REPEAT_WINDOW = Duration.ofMinutes(10);
    private static final int REPEAT_COUNT_THRESHOLD = 3;
    private static final int REPEAT_SCORE = 30;
    private static final int DECLINED_RETRY_COUNT_THRESHOLD = 2;
    private static final int DECLINED_RETRY_SCORE = 35;
    private static final int REVIEW_MIN_SCORE = 50;
    private static final int DENY_MIN_SCORE = 80;

    private final FdsEvaluationRepository evaluationRepository;
    private final FdsHighRiskCountryRepository highRiskCountries;
    private final FdsBlocklistEntryRepository blocklistEntries;
    private final FdsRulePolicyRepository rulePolicies;
    private final JsonMapper jsonMapper;

    @Autowired
    FdsEvaluationTransactionService(
            FdsEvaluationRepository evaluationRepository,
            FdsHighRiskCountryRepository highRiskCountries,
            FdsBlocklistEntryRepository blocklistEntries,
            FdsRulePolicyRepository rulePolicies,
            JsonMapper jsonMapper) {
        this.evaluationRepository = evaluationRepository;
        this.highRiskCountries = highRiskCountries;
        this.blocklistEntries = blocklistEntries;
        this.rulePolicies = rulePolicies;
        this.jsonMapper = jsonMapper;
    }

    FdsEvaluationTransactionService(FdsEvaluationRepository evaluationRepository) {
        this(evaluationRepository, null, null, null, JsonMapper.builder().build());
    }

    @Transactional
    public FdsEvaluationResponse evaluate(FdsEvaluationRequest request) {
        var existing = evaluationRepository.findByEvaluationRequestId(request.evaluationRequestId());
        if (existing.isPresent()) {
            return responseOf(requireSameRequest(existing.get(), request));
        }

        var highAmountMatched = isRuleActive("R01") && request.amount() >= HIGH_AMOUNT_THRESHOLD;
        var windowStart = request.paymentRequestedAt().minus(REPEAT_WINDOW);
        var priorPaymentIds = evaluationRepository
                .findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
                        request.merchantId(), request.customerRef(), windowStart, request.paymentRequestedAt())
                .stream()
                .map(FdsEvaluation::getPaymentId)
                .collect(java.util.stream.Collectors.toSet());
        priorPaymentIds.add(request.paymentId());
        var observedAttemptCount = priorPaymentIds.size();
        var repeatedPaymentMatched = isRuleActive("R04") && observedAttemptCount >= REPEAT_COUNT_THRESHOLD;
        var score = score(highAmountMatched, repeatedPaymentMatched);
        var history = java.util.Optional.ofNullable(evaluationRepository.findByMerchantIdAndCustomerRef(
                        request.merchantId(), request.customerRef()))
                .orElseGet(java.util.List::of);
        var approvedHistory = history.stream()
                .filter(e -> "APPROVED".equals(e.getAuthorizationResult()))
                .toList();
        var newDevice = isRuleActive("R02")
                && request.deviceId() != null
                && !approvedHistory.isEmpty()
                && approvedHistory.stream().noneMatch(e -> request.deviceId().equals(e.getDeviceId()));
        var newCard = isRuleActive("R03")
                && request.cardToken() != null
                && !approvedHistory.isEmpty()
                && approvedHistory.stream().noneMatch(e -> request.cardToken().equals(e.getCardToken()));
        var highRiskCountry = isRuleActive("R05")
                && request.ipCountryCode() != null
                && highRiskCountries != null
                && highRiskCountries.existsByCountryCodeAndActiveTrue(request.ipCountryCode());
        var windowHistory = history.stream()
                .filter(e -> isWithinWindow(e.getPaymentRequestedAt(), windowStart, request.paymentRequestedAt()))
                .toList();
        var smallPaymentIds = new java.util.HashSet<>(windowHistory.stream()
                .filter(e -> e.getRequestedAmount() <= 10_000)
                .map(FdsEvaluation::getPaymentId)
                .toList());
        if (request.amount() <= 10_000) {
            smallPaymentIds.add(request.paymentId());
        }
        var smallAttempts = smallPaymentIds.size();
        var distinctCards = java.util.stream.Stream.concat(
                        windowHistory.stream().map(FdsEvaluation::getCardToken),
                        java.util.stream.Stream.of(request.cardToken()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        var declined = windowHistory.stream()
                .filter(e -> "DECLINED".equals(e.getAuthorizationResult()))
                .map(FdsEvaluation::getPaymentId)
                .distinct()
                .count();
        var cardTesting = isRuleActive("R06")
                && request.cardToken() != null
                && smallAttempts >= 5
                && distinctCards >= 3
                && declined >= 3;
        var declinedRetry = isRuleActive("R08") && declined >= DECLINED_RETRY_COUNT_THRESHOLD;
        var blocked = isRuleActive("R07")
                && (isBlocked("BUYER_REF", request.customerRef(), request.paymentRequestedAt())
                        || isBlocked("DEVICE_ID", request.deviceId(), request.paymentRequestedAt())
                        || isBlocked("CARD_TOKEN", request.cardToken(), request.paymentRequestedAt()));
        score += (newDevice ? 25 : 0)
                + (newCard ? 25 : 0)
                + (highRiskCountry ? 40 : 0)
                + (cardTesting ? 45 : 0)
                + (declinedRetry ? DECLINED_RETRY_SCORE : 0)
                + (blocked ? 100 : 0);
        score = Math.min(100, score);
        var result = resultFor(score);
        var evaluatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var evaluation = FdsEvaluation.completed(request, result, score, evaluatedAt);
        evaluation.addRuleHit(
                "R01",
                highAmountMatched ? FdsRuleHitOutcome.MATCH : FdsRuleHitOutcome.NO_MATCH,
                highAmountMatched ? HIGH_AMOUNT_SCORE : 0,
                "고액 결제",
                "{\"amountGte\":%d,\"score\":%d}".formatted(HIGH_AMOUNT_THRESHOLD, HIGH_AMOUNT_SCORE),
                "{\"amount\":%d}".formatted(request.amount()),
                null,
                null);
        evaluation.addRuleHit(
                "R04",
                repeatedPaymentMatched ? FdsRuleHitOutcome.MATCH : FdsRuleHitOutcome.NO_MATCH,
                repeatedPaymentMatched ? REPEAT_SCORE : 0,
                "단시간 반복 결제",
                "{\"windowMinutes\":%d,\"attemptCountGte\":%d,\"score\":%d}"
                        .formatted(REPEAT_WINDOW.toMinutes(), REPEAT_COUNT_THRESHOLD, REPEAT_SCORE),
                "{\"attemptCount\":%d}".formatted(observedAttemptCount),
                windowStart,
                request.paymentRequestedAt());
        addRuleHit(
                evaluation,
                "R02",
                "신규 단말기",
                newDevice,
                25,
                "{\"requiresPriorApproved\":true}",
                "{\"deviceId\":" + json(request.deviceId()) + ",\"approvedHistoryCount\":" + approvedHistory.size()
                        + "}");
        addRuleHit(
                evaluation,
                "R03",
                "신규 카드",
                newCard,
                25,
                "{\"requiresPriorApproved\":true}",
                "{\"cardToken\":" + json(request.cardToken()) + ",\"approvedHistoryCount\":" + approvedHistory.size()
                        + "}");
        addRuleHit(
                evaluation,
                "R05",
                "고위험 국가 IP",
                highRiskCountry,
                40,
                "{\"managedList\":\"highRiskCountries\"}",
                "{\"ipCountryCode\":" + json(request.ipCountryCode()) + "}");
        addRuleHit(
                evaluation,
                "R06",
                "카드 테스팅",
                cardTesting,
                45,
                "{\"windowMinutes\":10,\"smallAmountLte\":10000,\"attemptCountGte\":5,\"cardCountGte\":3,\"declinedCountGte\":3}",
                "{\"smallAttemptCount\":" + smallAttempts + ",\"cardCount\":" + distinctCards + ",\"declinedCount\":"
                        + declined + "}");
        addRuleHit(
                evaluation,
                "R07",
                "차단 목록",
                blocked,
                100,
                "{\"active\":true}",
                "{\"buyerRef\":" + json(request.customerRef()) + "}");
        evaluation.addRuleHit(
                "R08",
                declinedRetry ? FdsRuleHitOutcome.MATCH : FdsRuleHitOutcome.NO_MATCH,
                declinedRetry ? DECLINED_RETRY_SCORE : 0,
                "거절 후 재시도",
                "{\"windowMinutes\":%d,\"declinedCountGte\":%d,\"score\":%d}"
                        .formatted(REPEAT_WINDOW.toMinutes(), DECLINED_RETRY_COUNT_THRESHOLD, DECLINED_RETRY_SCORE),
                "{\"declinedCount\":%d}".formatted(declined),
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

    private boolean isBlocked(String type, String value, Instant at) {
        return value != null
                && blocklistEntries != null
                && blocklistEntries.findByIdentifierTypeAndIdentifierValue(type, value).stream()
                        .anyMatch(entry -> entry.isActiveAt(at));
    }

    private boolean isRuleActive(String ruleCode) {
        return rulePolicies == null
                || rulePolicies
                        .findByRuleCode(ruleCode)
                        .map(policy -> policy.isActive())
                        .orElse(true);
    }

    private void addRuleHit(
            FdsEvaluation evaluation,
            String code,
            String name,
            boolean matched,
            int score,
            String threshold,
            String observed) {
        evaluation.addRuleHit(
                code,
                matched ? FdsRuleHitOutcome.MATCH : FdsRuleHitOutcome.NO_MATCH,
                matched ? score : 0,
                name,
                threshold,
                observed,
                null,
                null);
    }

    private boolean isWithinWindow(Instant requestedAt, Instant windowStart, Instant windowEnd) {
        return requestedAt.isAfter(windowStart) && !requestedAt.isAfter(windowEnd);
    }

    private String json(String value) {
        return jsonMapper.writeValueAsString(value);
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
