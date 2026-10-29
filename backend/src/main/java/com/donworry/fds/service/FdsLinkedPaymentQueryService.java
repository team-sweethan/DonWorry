package com.donworry.fds.service;

import com.donworry.fds.api.FdsAuthorizationResultRecorder.AuthorizationResult;
import com.donworry.fds.api.FdsLinkedPaymentReader;
import com.donworry.fds.api.FdsLinkedPayments;
import com.donworry.fds.api.FdsLinkedPayments.Match;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 집계 (#352)
// 사건 결제의 평가에서 비교할 값을 모으고, 같은 가맹점 · 기간 안의 평가와 원문으로 맞춘 뒤 숫자만 내보낸다
@Service
@Transactional(readOnly = true)
public class FdsLinkedPaymentQueryService implements FdsLinkedPaymentReader {

    private final FdsEvaluationRepository evaluationRepository;

    public FdsLinkedPaymentQueryService(FdsEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    @Override
    public FdsLinkedPayments findLinked(
            Long merchantId, String customerRef, Collection<Long> casePaymentIds, Instant since) {
        if (merchantId == null || customerRef == null || casePaymentIds == null || since == null) {
            throw new IllegalArgumentException("merchantId, customerRef, casePaymentIds, since must not be null");
        }
        Set<Long> excluded = Set.copyOf(casePaymentIds);
        // 다른 가맹점 평가는 비교할 값의 출처로도 쓰지 않는다
        List<FdsEvaluation> caseEvaluations = excluded.isEmpty()
                ? List.of()
                : evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(excluded).stream()
                        .filter(evaluation -> merchantId.equals(evaluation.getMerchantId()))
                        .toList();
        Scope scope = new Scope(merchantId, customerRef, excluded, since);
        return new FdsLinkedPayments(
                match(
                        scope,
                        caseEvaluations,
                        FdsEvaluation::getCardToken,
                        (values, s) ->
                                evaluationRepository
                                        .findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                                                s.merchantId(), values, s.since())),
                match(
                        scope,
                        caseEvaluations,
                        FdsEvaluation::getDeviceId,
                        (values, s) ->
                                evaluationRepository.findByMerchantIdAndDeviceIdInAndPaymentRequestedAtGreaterThanEqual(
                                        s.merchantId(), values, s.since())),
                match(
                        scope,
                        caseEvaluations,
                        FdsEvaluation::getIpAddress,
                        (values, s) ->
                                evaluationRepository
                                        .findByMerchantIdAndIpAddressInAndPaymentRequestedAtGreaterThanEqual(
                                                s.merchantId(), values, s.since())));
    }

    private static Match match(
            Scope scope,
            List<FdsEvaluation> caseEvaluations,
            Function<FdsEvaluation, String> identifier,
            BiFunction<Set<String>, Scope, List<FdsEvaluation>> query) {
        Set<String> values = caseEvaluations.stream()
                .map(identifier)
                .filter(FdsLinkedPaymentQueryService::present)
                .collect(Collectors.toSet());
        if (values.isEmpty()) {
            return Match.notProvided();
        }
        // DB 정렬 규칙이 대소문자를 무시해도 원문이 정확히 같은 값만 센다
        Map<Long, List<FdsEvaluation>> byPayment = query.apply(values, scope).stream()
                .filter(evaluation -> values.contains(identifier.apply(evaluation)))
                .filter(evaluation -> scope.merchantId().equals(evaluation.getMerchantId()))
                .filter(evaluation -> !scope.customerRef().equals(evaluation.getCustomerRef()))
                .filter(evaluation -> !scope.excludedPaymentIds().contains(evaluation.getPaymentId()))
                .collect(Collectors.groupingBy(FdsEvaluation::getPaymentId));
        // 재평가로 평가가 여러 개인 결제도 한 건으로 센다
        List<FdsEvaluation> latest = byPayment.values().stream()
                .map(FdsEvidenceQueryService::latestUsableOrLatestError)
                .toList();
        Map<FdsResult, Integer> resultCounts = new EnumMap<>(FdsResult.class);
        latest.forEach(evaluation -> resultCounts.merge(evaluation.getResult(), 1, Integer::sum));
        int declined = (int) byPayment.values().stream()
                .filter(evaluations -> evaluations.stream()
                        .anyMatch(e -> AuthorizationResult.DECLINED.name().equals(e.getAuthorizationResult())))
                .count();
        int customers = (int)
                latest.stream().map(FdsEvaluation::getCustomerRef).distinct().count();
        return new Match(true, customers, latest.size(), Collections.unmodifiableMap(resultCounts), declined);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record Scope(Long merchantId, String customerRef, Set<Long> excludedPaymentIds, Instant since) {}
}
