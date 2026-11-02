package com.donworry.fds.service;

import com.donworry.fds.api.FdsMlInput;
import com.donworry.fds.api.FdsMlInputReader;
import com.donworry.fds.api.FdsMlTarget;
import com.donworry.fds.api.FdsMlTransaction;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// ML Shadow 채점 입력 조회 (#409, ADR-0062) — 커밋된 평가만 읽고 평가 저장 경로는 건드리지 않는다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FdsMlInputQueryService implements FdsMlInputReader {

    // 같은 결제의 재평가 행 중 하나만 남길 때 기준 — 결제 입력(금액 · 시각 · 카드 · 기기)은 행마다 같다
    private static final Comparator<FdsEvaluation> HISTORY_ORDER = Comparator.comparing(
                    FdsEvaluation::getPaymentRequestedAt)
            .thenComparing(FdsEvaluation::getPaymentId)
            .thenComparing(FdsEvaluation::getEvaluationId);

    private final FdsEvaluationRepository evaluationRepository;

    @Override
    public List<FdsMlTarget> findNewTargets(long afterEvaluationId, Instant settledBefore, int limit) {
        if (settledBefore == null || limit <= 0) {
            throw new IllegalArgumentException("settledBefore must not be null and limit must be positive");
        }
        return evaluationRepository
                .findMlTargets(afterEvaluationId, settledBefore, FdsResult.ERROR, PageRequest.of(0, limit))
                .stream()
                .map(FdsMlInputQueryService::toTarget)
                .toList();
    }

    @Override
    public Optional<FdsMlInput> findInput(long evaluationId, int historyLimit) {
        if (historyLimit <= 0) {
            throw new IllegalArgumentException("historyLimit must be positive");
        }
        return evaluationRepository
                .findById(evaluationId)
                .filter(target -> target.getResult() != FdsResult.ERROR)
                .map(target -> buildInput(target, historyLimit));
    }

    private FdsMlInput buildInput(FdsEvaluation target, int historyLimit) {
        Instant at = target.getPaymentRequestedAt();
        List<FdsEvaluation> customerHistory = onePerPayment(evaluationRepository
                .findByMerchantIdAndCustomerRefAndPaymentRequestedAtLessThanEqual(
                        target.getMerchantId(), target.getCustomerRef(), at)
                .stream()
                .filter(row -> !row.getPaymentId().equals(target.getPaymentId()))
                .toList());
        List<FdsEvaluation> sharedCardHistory = target.getCardToken() == null
                ? List.of()
                : onePerPayment(evaluationRepository
                        .findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
                                target.getMerchantId(), Set.of(target.getCardToken()), Instant.EPOCH)
                        .stream()
                        // 같은 가맹점 안에서만 — 학습 데이터의 도난 카드도 같은 가맹점 안에서만 만들어졌다
                        .filter(row -> !row.getPaymentRequestedAt().isAfter(at))
                        .filter(row -> !row.getCustomerRef().equals(target.getCustomerRef()))
                        .toList());

        var aliases = new Aliases(target.getCustomerRef());
        var targetRow = aliases.toTransaction(target);
        if (customerHistory.size() + sharedCardHistory.size() > historyLimit) {
            return FdsMlInput.builder()
                    .target(targetRow)
                    .customerHistory(List.of())
                    .sharedCardHistory(List.of())
                    .historyOverLimit(true)
                    .build();
        }
        return FdsMlInput.builder()
                .target(targetRow)
                .customerHistory(
                        customerHistory.stream().map(aliases::toTransaction).toList())
                .sharedCardHistory(
                        sharedCardHistory.stream().map(aliases::toTransaction).toList())
                .historyOverLimit(false)
                .build();
    }

    // 재평가로 같은 결제의 평가가 여러 행이면 하나만 — 두 번 세면 건수 · 평균이 틀어지고 ai-server가 422로 거절한다
    private static List<FdsEvaluation> onePerPayment(Collection<FdsEvaluation> rows) {
        Map<Long, FdsEvaluation> byPayment = new LinkedHashMap<>();
        rows.stream().sorted(HISTORY_ORDER).forEach(row -> byPayment.putIfAbsent(row.getPaymentId(), row));
        return List.copyOf(byPayment.values());
    }

    private static FdsMlTarget toTarget(FdsEvaluation evaluation) {
        return FdsMlTarget.builder()
                .evaluationId(evaluation.getEvaluationId())
                .paymentId(evaluation.getPaymentId())
                .result(evaluation.getResult())
                .riskScore(evaluation.getRiskScore())
                .evaluatedAt(evaluation.getEvaluatedAt())
                .build();
    }

    // 응답 하나 안에서만 통하는 별칭 — 처음 나온 순서대로 대상 고객 T, 다른 고객 O1 · O2, 카드 C1, 기기 D1
    private static final class Aliases {

        private final String targetCustomerRef;
        private final Map<String, String> customers = new HashMap<>();
        private final Map<String, String> cards = new HashMap<>();
        private final Map<String, String> devices = new HashMap<>();

        private Aliases(String targetCustomerRef) {
            this.targetCustomerRef = targetCustomerRef;
        }

        private FdsMlTransaction toTransaction(FdsEvaluation row) {
            return FdsMlTransaction.builder()
                    .paymentId(row.getPaymentId())
                    .merchantId(row.getMerchantId())
                    .customerKey(customer(row.getCustomerRef()))
                    .amount(row.getRequestedAmount())
                    .paymentRequestedAt(row.getPaymentRequestedAt())
                    .deviceKey(alias(devices, "D", row.getDeviceId()))
                    .cardKey(alias(cards, "C", row.getCardToken()))
                    .build();
        }

        private String customer(String customerRef) {
            if (customerRef.equals(targetCustomerRef)) {
                return "T";
            }
            return alias(customers, "O", customerRef);
        }

        private static String alias(Map<String, String> aliases, String prefix, String value) {
            if (value == null) {
                return null;
            }
            return aliases.computeIfAbsent(value, ignored -> prefix + (aliases.size() + 1));
        }
    }
}
