package com.donworry.investigation.report.service;

import com.donworry.fds.api.FdsPaymentMatchReader;
import com.donworry.fds.api.FdsPaymentMatches;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.LinkedCasesToolResponse;
import com.donworry.investigation.report.dto.LinkedCasesToolResponse.LinkedCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 같은 카드 · 기기 · IP로 열린 지난 사건 (#821) — P3 사건 · 판정은 읽기만, 일치 비교는 P2 공개 창구가 원문으로 한다
// 방식 (나): 다른 고객의 종료 사건 결제 번호를 P2에 넘기고, 그중 같은 값인 결제 번호만 돌려받는다
// 지난 판정은 참고 사실이다 — 이번 사건 등급 · 정상 사유를 바꾸지 않는다
@Component
@RequiredArgsConstructor
public class LinkedCaseFinder {

    static final String SOURCE = "P3·P2";
    static final int LOOKBACK_DAYS = 90;
    static final int MAX_CASES = 5;
    static final String CARD = "CARD";
    static final String DEVICE = "DEVICE";
    static final String IP = "IP";

    // 카드 · 기기는 코드가 늘 보고(기본 조회), IP는 공용 와이파이일 수 있어 AI가 볼지 고른다 (11/14 호출 방식 C)
    // 그 밖의 값은 받지 않는다 — 넓은 범위를 받으면 조회 계약이 흐려진다
    static final Map<String, List<String>> KINDS_BY = Map.of("CARD_DEVICE", List.of(CARD, DEVICE), "IP", List.of(IP));

    private static final Map<String, Function<FdsPaymentMatches, FdsPaymentMatches.Kind>> MATCH_OF =
            Map.of(CARD, FdsPaymentMatches::card, DEVICE, FdsPaymentMatches::device, IP, FdsPaymentMatches::ip);

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentDecisionRepository paymentDecisionRepository;
    private final FdsPaymentMatchReader fdsPaymentMatchReader;

    // 별도 읽기 트랜잭션 — 다른 조회와 같이 실패가 바깥 트랜잭션을 되돌림 전용으로 만들지 않게 (SimilarCaseFinder와 같다)
    // 허용 밖 by는 사건을 읽기 전에 400으로 거절한다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public LinkedCasesToolResponse find(Long caseId, String by, Instant now) {
        List<String> kinds = by == null ? null : KINDS_BY.get(by);
        if (kinds == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 범위는 CARD_DEVICE · IP만 됩니다.");
        }
        SecurityCase current = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        List<Long> casePaymentIds = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                .map(CasePayment::getPaymentId)
                .toList();
        // 같은 고객 사건은 find_similar_cases가 이미 낸다. 열린 사건은 판정이 없고 담당자만 열 수 있어 넣지 않는다
        List<SecurityCase> pastCases = securityCaseRepository
                .findTop100ByMerchantIdAndCustomerRefNotAndStatusAndClosedAtGreaterThanEqualOrderByClosedAtDesc(
                        current.getMerchantId(),
                        current.getCustomerRef(),
                        CaseStatus.CLOSED,
                        now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS))
                .stream()
                .filter(c -> !c.getId().equals(caseId))
                .toList();
        Map<Long, Long> caseOfPayment = candidates(pastCases);
        FdsPaymentMatches matches =
                fdsPaymentMatchReader.findMatching(current.getMerchantId(), casePaymentIds, caseOfPayment.keySet());

        // 지난 사건마다 겹친 결제와 종류 — 최근 종료 순(pastCases 순서), 종류는 kinds 순서(카드 → 기기)
        Map<Long, Set<Long>> matchedPayments = new LinkedHashMap<>();
        Map<Long, Set<String>> matchedBy = new LinkedHashMap<>();
        pastCases.forEach(c -> {
            matchedPayments.put(c.getId(), new HashSet<>());
            matchedBy.put(c.getId(), new LinkedHashSet<>());
        });
        for (String kind : kinds) {
            for (Long paymentId : MATCH_OF.get(kind).apply(matches).paymentIds()) {
                Long pastCaseId = caseOfPayment.get(paymentId);
                if (pastCaseId == null) {
                    continue;
                }
                matchedPayments.get(pastCaseId).add(paymentId);
                matchedBy.get(pastCaseId).add(kind);
            }
        }
        List<SecurityCase> matched = pastCases.stream()
                .filter(c -> !matchedPayments.get(c.getId()).isEmpty())
                .toList();
        List<SecurityCase> shown = matched.stream().limit(MAX_CASES).toList();
        Map<Long, PaymentDecision> decisionOfPayment = shown.isEmpty()
                ? Map.of()
                : paymentDecisionRepository
                        .findByCasePaymentSecurityCaseIdIn(
                                shown.stream().map(SecurityCase::getId).toList())
                        .stream()
                        .collect(Collectors.toMap(d -> d.getCasePayment().getPaymentId(), d -> d, (a, b) -> a));
        List<LinkedCase> cases = shown.stream()
                .map(c -> linkedCase(c, matchedBy.get(c.getId()), matchedPayments.get(c.getId()), decisionOfPayment))
                .toList();
        List<String> notProvided = kinds.stream()
                .filter(kind -> !MATCH_OF.get(kind).apply(matches).provided())
                .toList();
        return LinkedCasesToolResponse.builder()
                .caseId(String.valueOf(caseId))
                .evidenceVersion(current.getEvidenceVersion())
                .source(SOURCE)
                .retrievedAt(now)
                .by(by)
                .lookbackDays(LOOKBACK_DAYS)
                .kinds(kinds)
                .notProvided(notProvided)
                .cases(cases)
                .totalMatched(matched.size())
                .build();
    }

    // 지난 사건 결제 → 사건. P2 후보 상한을 넘기 전까지 최근 종료 사건부터 사건 단위로 담는다 (사건을 반만 담지 않는다)
    private Map<Long, Long> candidates(List<SecurityCase> pastCases) {
        if (pastCases.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> paymentsByCase =
                casePaymentRepository
                        .findBySecurityCaseIdIn(
                                pastCases.stream().map(SecurityCase::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                p -> p.getSecurityCase().getId(),
                                Collectors.mapping(CasePayment::getPaymentId, Collectors.toList())));
        Map<Long, Long> caseOfPayment = new LinkedHashMap<>();
        for (SecurityCase pastCase : pastCases) {
            List<Long> payments = paymentsByCase.getOrDefault(pastCase.getId(), List.of());
            if (caseOfPayment.size() + payments.size() > FdsPaymentMatchReader.MAX_CANDIDATES) {
                break;
            }
            payments.forEach(paymentId -> caseOfPayment.put(paymentId, pastCase.getId()));
        }
        return caseOfPayment;
    }

    private static LinkedCase linkedCase(
            SecurityCase pastCase,
            Set<String> matchedBy,
            Set<Long> payments,
            Map<Long, PaymentDecision> decisionOfPayment) {
        List<Decision> decisions = payments.stream()
                .map(decisionOfPayment::get)
                .filter(d -> d != null)
                .map(PaymentDecision::getDecision)
                .toList();
        return LinkedCase.builder()
                .caseId(String.valueOf(pastCase.getId()))
                .matchedBy(List.copyOf(matchedBy))
                .closedAt(pastCase.getClosedAt())
                .paymentCount(payments.size())
                .acceptCount((int)
                        decisions.stream().filter(d -> d == Decision.ACCEPT).count())
                .rejectCount((int)
                        decisions.stream().filter(d -> d == Decision.REJECT).count())
                .build();
    }
}
