package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationDetail;
import com.donworry.fds.api.FdsEvaluationDetail.FdsBlocklistCandidate;
import com.donworry.fds.api.FdsEvaluationDetail.FdsRuleHitDetail;
import com.donworry.fds.api.FdsEvaluationPage;
import com.donworry.fds.api.FdsEvaluationSummary;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.api.FdsRuleCatalogEntry;
import com.donworry.fds.api.FdsRuleHitOutcome;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FdsEvaluationQueryService {

    private static final List<RuleDefinition> PAYMENT_RULES = List.of(
            new RuleDefinition(
                    "R01", "금액", "고액 결제", "단건 결제 금액이 50만 원 이상", "+30점", "65만 원 단체 케이크 세트", "30점으로 단독 ALLOW", false),
            new RuleDefinition(
                    "R02",
                    "신규성",
                    "신규 단말기",
                    "정상 승인 이력이 있는 구매자가 기존에 없던 deviceId 사용",
                    "+25점",
                    "평소 기기와 다른 휴대폰에서 결제",
                    "25점으로 단독 ALLOW",
                    false),
            new RuleDefinition(
                    "R03",
                    "신규성",
                    "신규 카드",
                    "정상 승인 이력이 있는 구매자가 기존에 없던 cardToken 사용",
                    "+25점",
                    "평소 카드 A 고객이 카드 B로 결제",
                    "25점으로 단독 ALLOW",
                    false),
            new RuleDefinition(
                    "R04",
                    "빈도",
                    "단시간 반복 결제",
                    "동일 구매자가 10분 동안 3회 이상 결제 시도",
                    "+30점",
                    "10분 안 같은 구매자 결제 3회",
                    "30점으로 단독 ALLOW",
                    false),
            new RuleDefinition(
                    "R05",
                    "접속 환경",
                    "고위험 국가 IP",
                    "검증된 IP 국가 코드가 관리 중인 고위험 국가 목록과 일치",
                    "+40점",
                    "고위험 국가로 분류된 IP에서 결제",
                    "40점으로 단독 ALLOW",
                    false),
            new RuleDefinition(
                    "R06",
                    "카드 테스팅",
                    "카드 테스팅",
                    "10분 동안 소액 5회 이상, 서로 다른 카드 3개 이상, 승인 실패 3회 이상",
                    "+45점",
                    "소액 결제를 여러 카드로 반복 시도",
                    "45점으로 단독 ALLOW",
                    false),
            new RuleDefinition(
                    "R07",
                    "차단",
                    "차단 목록",
                    "활성 차단 식별값(cardToken · deviceId · IP 주소 · 이메일 · buyerRef)과 정확히 일치",
                    "100점 · 즉시 DENY",
                    "차단된 기기 또는 카드 토큰으로 재시도",
                    "점수 합산 전 즉시 차단",
                    true),
            new RuleDefinition(
                    "R08",
                    "거절 이력",
                    "거절 후 재시도",
                    "동일 구매자가 10분 동안 실제 거절을 2회 이상 경험한 뒤 재시도",
                    "+35점",
                    "거절 2회 뒤 같은 구매자가 다시 결제",
                    "35점으로 단독 ALLOW",
                    false));

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
                            criteriaBuilder.like(root.get("evaluationId").as(String.class), "%" + number + "%"),
                            criteriaBuilder.like(root.get("paymentId").as(String.class), "%" + number + "%"),
                            criteriaBuilder.like(root.get("merchantId").as(String.class), "%" + number + "%")));
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
        var blocklistCandidates = new ArrayList<FdsBlocklistCandidate>();
        addBlocklistCandidate(
                blocklistCandidates, "CARD_TOKEN", evaluation.getCardToken(), evaluation.getCardDisplayLabel());
        addBlocklistCandidate(blocklistCandidates, "DEVICE_ID", evaluation.getDeviceId(), null);
        addBlocklistCandidate(blocklistCandidates, "IP_ADDRESS", evaluation.getIpAddress(), null);
        addBlocklistCandidate(blocklistCandidates, "BUYER_EMAIL", evaluation.getBuyerEmail(), null);
        var cardIdentifier = evaluation.getCardToken() == null
                ? null
                : new FdsEvaluationDetail.CardIdentifier(evaluation.getCardToken(), evaluation.getCardDisplayLabel());
        return new FdsEvaluationDetail(
                summary(evaluation), ruleHits, history, List.copyOf(blocklistCandidates), cardIdentifier);
    }

    private void addBlocklistCandidate(
            List<FdsBlocklistCandidate> candidates, String type, String value, String displayLabel) {
        if (value != null) {
            candidates.add(new FdsBlocklistCandidate(type, value, displayLabel));
        }
    }

    public List<FdsRuleCatalogEntry> ruleCatalog() {
        var today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        var start = today.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC);
        var frequencies = new HashMap<String, RuleFrequency>();
        for (var evaluation : evaluationRepository.findByEvaluatedAtGreaterThanEqual(start)) {
            var dayIndex = (int) ChronoUnit.DAYS.between(
                    start.atZone(ZoneOffset.UTC).toLocalDate(),
                    evaluation.getEvaluatedAt().atZone(ZoneOffset.UTC).toLocalDate());
            for (var hit : evaluation.getRuleHits()) {
                if (hit.getOutcome() == FdsRuleHitOutcome.MATCH) {
                    frequencies
                            .computeIfAbsent(hit.getRuleCode(), ignored -> new RuleFrequency())
                            .add(dayIndex, evaluation.getEvaluatedAt());
                }
            }
        }
        return PAYMENT_RULES.stream()
                .map(rule -> rule.toEntry(frequencies.get(rule.code())))
                .toList();
    }

    private FdsEvaluationSummary summary(FdsEvaluation evaluation) {
        return new FdsEvaluationSummary(
                evaluation.getEvaluationId(),
                evaluation.getPaymentId(),
                evaluation.getMerchantId(),
                evaluation.getCustomerRef(),
                evaluation.getRiskScore(),
                evaluation.getResult(),
                evaluation.getEvaluatedAt(),
                evaluation.getErrorCode(),
                evaluation.getPaymentRequestedAt());
    }

    private record RuleDefinition(
            String code,
            String category,
            String name,
            String condition,
            String resultDescription,
            String example,
            String standaloneDescription,
            boolean immediateDeny) {

        private FdsRuleCatalogEntry toEntry(RuleFrequency frequency) {
            var resolved = frequency == null ? new RuleFrequency() : frequency;
            return new FdsRuleCatalogEntry(
                    code,
                    category,
                    name,
                    condition,
                    resultDescription,
                    example,
                    standaloneDescription,
                    immediateDeny,
                    resolved.count,
                    resolved.lastMatchedAt,
                    List.copyOf(resolved.dailyCounts));
        }
    }

    private static final class RuleFrequency {

        private final List<Long> dailyCounts = new ArrayList<>(java.util.Collections.nCopies(7, 0L));
        private long count;
        private Instant lastMatchedAt;

        private void add(int dayIndex, Instant matchedAt) {
            count++;
            if (dayIndex >= 0 && dayIndex < dailyCounts.size()) {
                dailyCounts.set(dayIndex, dailyCounts.get(dayIndex) + 1);
            }
            if (lastMatchedAt == null || matchedAt.isAfter(lastMatchedAt)) {
                lastMatchedAt = matchedAt;
            }
        }
    }
}
