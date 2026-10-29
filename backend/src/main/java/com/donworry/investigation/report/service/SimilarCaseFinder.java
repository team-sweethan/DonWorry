package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse.SharedRule;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse.SimilarCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 비슷한 과거 사건 찾기 (#296) — AI Tool과 AI를 끈 고정 보고서가 같이 쓴다. P3 데이터는 읽기만 한다
// 같은 고객(같은 가맹점 + customer_ref)을 먼저, 그다음 적중 규칙 2개 이상 겹침. 각각 최근 종료 순, 최대 5건
@Component
@RequiredArgsConstructor
public class SimilarCaseFinder {

    static final String SOURCE = "P3·P2";
    static final String SAME_CUSTOMER = "SAME_CUSTOMER";
    static final String SHARED_RULES = "SHARED_RULES";
    static final int MAX_CASES = 5;
    static final int MIN_SHARED_RULES = 2;

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentDecisionRepository paymentDecisionRepository;
    private final PaymentInfoReader paymentInfoReader;

    // 별도 읽기 트랜잭션 — 고정 보고서가 이 조회 실패를 잡아 넘겨도 보고서 트랜잭션이 되돌림 전용이 되지 않게 한다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SimilarCasesToolResponse find(Long caseId) {
        SecurityCase current = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        List<SecurityCase> sameCustomer = securityCaseRepository
                .findByMerchantIdAndCustomerRefAndStatusOrderByClosedAtDesc(
                        current.getMerchantId(), current.getCustomerRef(), CaseStatus.CLOSED)
                .stream()
                .filter(c -> !c.getId().equals(caseId))
                .toList();
        Set<Long> sameCustomerIds =
                sameCustomer.stream().map(SecurityCase::getId).collect(Collectors.toSet());
        List<SecurityCase> recentClosed =
                securityCaseRepository.findTop100ByStatusOrderByClosedAtDesc(CaseStatus.CLOSED).stream()
                        .filter(c -> !c.getId().equals(caseId) && !sameCustomerIds.contains(c.getId()))
                        .toList();

        // 지금 사건과 후보 사건의 결제 → 적중 규칙을 한 번에 읽는다
        List<Long> caseIds = new ArrayList<>();
        caseIds.add(caseId);
        sameCustomer.forEach(c -> caseIds.add(c.getId()));
        recentClosed.forEach(c -> caseIds.add(c.getId()));
        Map<Long, List<Long>> paymentsByCase = casePaymentRepository.findBySecurityCaseIdIn(caseIds).stream()
                .collect(Collectors.groupingBy(
                        p -> p.getSecurityCase().getId(),
                        Collectors.mapping(CasePayment::getPaymentId, Collectors.toList())));
        List<Long> paymentIds =
                paymentsByCase.values().stream().flatMap(List::stream).toList();
        Map<Long, PaymentInfo> infos = paymentIds.isEmpty() ? Map.of() : paymentInfoReader.findAll(paymentIds);
        Map<String, String> currentRules = rulesOf(paymentsByCase.get(caseId), infos);

        List<Match> matches = new ArrayList<>();
        sameCustomer.forEach(c -> matches.add(new Match(
                c, SAME_CUSTOMER, shared(currentRules, rulesOf(paymentsByCase.get(c.getId()), infos)))));
        for (SecurityCase c : recentClosed) {
            List<SharedRule> shared = shared(currentRules, rulesOf(paymentsByCase.get(c.getId()), infos));
            if (shared.size() >= MIN_SHARED_RULES) {
                matches.add(new Match(c, SHARED_RULES, shared));
            }
        }
        List<Match> shown = matches.stream().limit(MAX_CASES).toList();
        Map<Long, List<PaymentDecision>> decisions = shown.isEmpty()
                ? Map.of()
                : paymentDecisionRepository
                        .findByCasePaymentSecurityCaseIdIn(shown.stream()
                                .map(m -> m.securityCase().getId())
                                .toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                d -> d.getCasePayment().getSecurityCase().getId()));
        List<SimilarCase> cases = shown.stream()
                .map(m ->
                        toSimilarCase(m, decisions.getOrDefault(m.securityCase().getId(), List.of())))
                .toList();
        return new SimilarCasesToolResponse(
                String.valueOf(caseId),
                current.getEvidenceVersion(),
                SOURCE,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                cases,
                matches.size());
    }

    // 사건에 걸린 규칙 코드 → 이름 (여러 결제에 같은 규칙이 걸리면 한 번만)
    private static Map<String, String> rulesOf(Collection<Long> paymentIds, Map<Long, PaymentInfo> infos) {
        Map<String, String> rules = new LinkedHashMap<>();
        if (paymentIds == null) {
            return rules;
        }
        paymentIds.stream()
                .map(infos::get)
                .filter(Objects::nonNull)
                .flatMap(info -> info.ruleHits().stream())
                .forEach(rule -> rules.putIfAbsent(rule.code(), ruleName(rule)));
        return rules;
    }

    private static String ruleName(RuleHit rule) {
        return rule.name() == null ? rule.code() : rule.name();
    }

    private static List<SharedRule> shared(Map<String, String> current, Map<String, String> other) {
        return current.entrySet().stream()
                .filter(e -> other.containsKey(e.getKey()))
                .map(e -> new SharedRule(e.getKey(), e.getValue()))
                .toList();
    }

    // 결과는 결제별 직원 판정 건수 그대로 — 매입 진행(ACCEPT) N건 · 승인 취소(REJECT) N건
    private static SimilarCase toSimilarCase(Match match, List<PaymentDecision> decisions) {
        int accept = (int) decisions.stream()
                .filter(d -> d.getDecision() == Decision.ACCEPT)
                .count();
        int reject = (int) decisions.stream()
                .filter(d -> d.getDecision() == Decision.REJECT)
                .count();
        return new SimilarCase(
                String.valueOf(match.securityCase().getId()),
                match.similarity(),
                match.sharedRules(),
                match.securityCase().getClosedAt(),
                accept,
                reject);
    }

    private record Match(SecurityCase securityCase, String similarity, List<SharedRule> sharedRules) {}
}
