package com.donworry.fds.service;

import com.donworry.fds.api.FdsAuthorizationResultRecorder.AuthorizationResult;
import com.donworry.fds.api.FdsLinkedPaymentReader;
import com.donworry.fds.api.FdsLinkedPayments;
import com.donworry.fds.api.FdsLinkedPayments.Match;
import com.donworry.fds.api.FdsLinkedPayments.Nearby;
import com.donworry.fds.api.FdsLinkedPayments.PaymentMatch;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
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
// 사건 전체와 사건 결제별로 센다 — 조회는 종류마다 한 번, 결제별 집계는 그 결과를 다시 센다 (#533)
// 사건 결제 요청 시각 앞뒤 nearbyWindow 안의 결제도 그 결과에서 다시 센다 — 조회를 늘리지 않는다 (#637)
@Service
@Transactional(readOnly = true)
public class FdsLinkedPaymentQueryService implements FdsLinkedPaymentReader {

    private final FdsEvaluationRepository evaluationRepository;

    public FdsLinkedPaymentQueryService(FdsEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    @Override
    public FdsLinkedPayments findLinked(
            Long merchantId,
            String customerRef,
            Collection<Long> casePaymentIds,
            Instant since,
            Duration nearbyWindow) {
        if (merchantId == null
                || customerRef == null
                || casePaymentIds == null
                || since == null
                || nearbyWindow == null) {
            throw new IllegalArgumentException(
                    "merchantId, customerRef, casePaymentIds, since, nearbyWindow must not be null");
        }
        if (nearbyWindow.isNegative()) {
            throw new IllegalArgumentException("nearbyWindow must not be negative");
        }
        Set<Long> excluded = Set.copyOf(casePaymentIds);
        // 다른 가맹점 평가는 비교할 값의 출처로도 쓰지 않는다
        List<FdsEvaluation> caseEvaluations = excluded.isEmpty()
                ? List.of()
                : evaluationRepository.findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(excluded).stream()
                        .filter(evaluation -> merchantId.equals(evaluation.getMerchantId()))
                        .toList();
        Scope scope = new Scope(merchantId, customerRef, excluded, since);
        Linked card = linked(
                scope,
                caseEvaluations,
                FdsEvaluation::getCardToken,
                (values, s) -> evaluationRepository.findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                        s.merchantId(), values, s.since()));
        Linked device = linked(
                scope,
                caseEvaluations,
                FdsEvaluation::getDeviceId,
                (values, s) -> evaluationRepository.findByMerchantIdAndDeviceIdInAndPaymentRequestedAtGreaterThanEqual(
                        s.merchantId(), values, s.since()));
        Linked ip = linked(
                scope,
                caseEvaluations,
                FdsEvaluation::getIpAddress,
                (values, s) -> evaluationRepository.findByMerchantIdAndIpAddressInAndPaymentRequestedAtGreaterThanEqual(
                        s.merchantId(), values, s.since()));
        // 사건 결제 순서를 지키고 같은 결제는 한 번만 — 평가가 없는 결제는 모두 "정보 없음"
        List<PaymentMatch> payments = new LinkedHashSet<>(casePaymentIds)
                .stream()
                        .map(paymentId -> new PaymentMatch(
                                paymentId,
                                card.forPayment(paymentId, nearbyWindow),
                                device.forPayment(paymentId, nearbyWindow),
                                ip.forPayment(paymentId, nearbyWindow)))
                        .toList();
        return new FdsLinkedPayments(card.all(nearbyWindow), device.all(nearbyWindow), ip.all(nearbyWindow), payments);
    }

    // 비교할 값으로 한 번 조회하고, 다른 고객 결제만 남긴다
    private static Linked linked(
            Scope scope,
            List<FdsEvaluation> caseEvaluations,
            Function<FdsEvaluation, String> identifier,
            BiFunction<Set<String>, Scope, List<FdsEvaluation>> query) {
        Set<String> values = values(caseEvaluations, identifier);
        if (values.isEmpty()) {
            return new Linked(identifier, caseEvaluations, List.of());
        }
        // DB 정렬 규칙이 대소문자를 무시해도 원문이 정확히 같은 값만 센다
        List<FdsEvaluation> found = query.apply(values, scope).stream()
                .filter(evaluation -> values.contains(identifier.apply(evaluation)))
                .filter(evaluation -> scope.merchantId().equals(evaluation.getMerchantId()))
                .filter(evaluation -> !scope.customerRef().equals(evaluation.getCustomerRef()))
                .filter(evaluation -> !scope.excludedPaymentIds().contains(evaluation.getPaymentId()))
                .toList();
        return new Linked(identifier, caseEvaluations, found);
    }

    private static Set<String> values(List<FdsEvaluation> evaluations, Function<FdsEvaluation, String> identifier) {
        return evaluations.stream()
                .map(identifier)
                .filter(FdsLinkedPaymentQueryService::present)
                .collect(Collectors.toSet());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record Scope(Long merchantId, String customerRef, Set<Long> excludedPaymentIds, Instant since) {}

    // 한 종류(카드 · 기기 · IP)의 조회 결과 — 사건 전체 값으로도, 결제 하나의 값으로도 센다
    private record Linked(
            Function<FdsEvaluation, String> identifier,
            List<FdsEvaluation> caseEvaluations,
            List<FdsEvaluation> found) {

        Match all(Duration nearbyWindow) {
            return count(caseEvaluations, nearbyWindow);
        }

        Match forPayment(Long paymentId, Duration nearbyWindow) {
            List<FdsEvaluation> own = caseEvaluations.stream()
                    .filter(evaluation -> paymentId.equals(evaluation.getPaymentId()))
                    .toList();
            return count(own, nearbyWindow);
        }

        // source: 비교할 값을 낸 사건 결제 평가 (사건 전체 또는 결제 하나)
        private Match count(List<FdsEvaluation> source, Duration nearbyWindow) {
            Set<String> values = values(source, identifier);
            if (values.isEmpty()) {
                return Match.notProvided();
            }
            Map<Long, List<FdsEvaluation>> byPayment = found.stream()
                    .filter(evaluation -> values.contains(identifier.apply(evaluation)))
                    .collect(Collectors.groupingBy(FdsEvaluation::getPaymentId));
            Nearby all = tally(byPayment.values());
            // 같은 값을 낸 사건 결제 중 하나라도 앞뒤 창 안에 있으면 "몰린 결제"로 센다
            Nearby nearby = tally(byPayment.values().stream()
                    .filter(evaluations ->
                            near(FdsEvidenceQueryService.latestUsableOrLatestError(evaluations), source, nearbyWindow))
                    .toList());
            return Match.builder()
                    .provided(true)
                    .otherCustomerCount(all.otherCustomerCount())
                    .paymentCount(all.paymentCount())
                    .resultCounts(all.resultCounts())
                    .declinedCount(all.declinedCount())
                    .nearby(nearby)
                    .build();
        }

        // 결제 요청 시각으로 비교한다 (평가 시각은 재평가 때 바뀐다). 경계 포함 — 정확히 창 길이만큼 떨어져도 안이다
        private boolean near(FdsEvaluation other, List<FdsEvaluation> source, Duration nearbyWindow) {
            String value = identifier.apply(other);
            Instant at = other.getPaymentRequestedAt();
            if (at == null) {
                return false;
            }
            return source.stream()
                    .filter(own -> value.equals(identifier.apply(own)))
                    .map(FdsEvaluation::getPaymentRequestedAt)
                    .filter(ownAt -> ownAt != null)
                    .anyMatch(ownAt -> Duration.between(ownAt, at).abs().compareTo(nearbyWindow) <= 0);
        }

        // 다른 고객 결제 묶음(결제마다 평가 목록)의 고객 수 · 결제 수 · 판정 · 카드사 거절 — 30일 합계와 앞뒤 창 안이 같은 셈을 쓴다
        private static Nearby tally(Collection<List<FdsEvaluation>> payments) {
            // 재평가로 평가가 여러 개인 결제도 한 건으로 센다
            List<FdsEvaluation> latest = payments.stream()
                    .map(FdsEvidenceQueryService::latestUsableOrLatestError)
                    .toList();
            Map<FdsResult, Integer> resultCounts = new EnumMap<>(FdsResult.class);
            latest.forEach(evaluation -> resultCounts.merge(evaluation.getResult(), 1, Integer::sum));
            int declined = (int) payments.stream()
                    .filter(evaluations -> evaluations.stream()
                            .anyMatch(e -> AuthorizationResult.DECLINED.name().equals(e.getAuthorizationResult())))
                    .count();
            int customers = (int) latest.stream()
                    .map(FdsEvaluation::getCustomerRef)
                    .distinct()
                    .count();
            return Nearby.builder()
                    .otherCustomerCount(customers)
                    .paymentCount(latest.size())
                    .resultCounts(Collections.unmodifiableMap(resultCounts))
                    .declinedCount(declined)
                    .build();
        }
    }
}
