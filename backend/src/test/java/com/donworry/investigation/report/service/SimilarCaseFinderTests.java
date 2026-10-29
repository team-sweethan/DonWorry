package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse.SimilarCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// 비슷한 과거 사건 고르기 규칙 (#296) — 저장소는 목으로 바꿔 고르기 · 정렬 · 결과 세기만 본다
// 쿼리 자체(종료 사건 · 같은 고객 조건)는 mariaDbTest 몫이라 목은 쿼리 조건대로 걸러 돌려준다
@ExtendWith(MockitoExtension.class)
class SimilarCaseFinderTests {

    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    static final RuleHit HIGH = new RuleHit("R01", "고액 결제", 30);
    static final RuleHit NEW_CARD = new RuleHit("R03", "결제 이력 없는 카드", 25);
    static final RuleHit REPEAT = new RuleHit("R04", "특정 횟수 이상 결제", 20);

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CasePaymentRepository casePaymentRepository;

    @Mock
    PaymentDecisionRepository paymentDecisionRepository;

    @Mock
    PaymentInfoReader paymentInfoReader;

    final List<SecurityCase> cases = new ArrayList<>();
    final List<CasePayment> payments = new ArrayList<>();
    final Map<Long, PaymentInfo> infos = new HashMap<>();
    final List<PaymentDecision> decisions = new ArrayList<>();
    long nextPaymentId = 1000;

    SimilarCaseFinder finder;

    @BeforeEach
    void setUp() {
        finder = new SimilarCaseFinder(
                securityCaseRepository, casePaymentRepository, paymentDecisionRepository, paymentInfoReader);
        lenient()
                .when(securityCaseRepository.findById(anyLong()))
                .thenAnswer(inv -> cases.stream()
                        .filter(c -> c.getId().equals(inv.getArgument(0)))
                        .findFirst());
        lenient()
                .when(securityCaseRepository.findByMerchantIdAndCustomerRefAndStatusOrderByClosedAtDesc(
                        anyLong(), anyString(), any()))
                .thenAnswer(inv -> closedNewestFirst().stream()
                        .filter(c -> c.getMerchantId().equals(inv.getArgument(0))
                                && c.getCustomerRef().equals(inv.getArgument(1)))
                        .toList());
        lenient()
                .when(securityCaseRepository.findTop100ByStatusOrderByClosedAtDesc(CaseStatus.CLOSED))
                .thenAnswer(inv -> closedNewestFirst());
        lenient()
                .when(casePaymentRepository.findBySecurityCaseIdIn(anyCollection()))
                .thenAnswer(inv -> payments.stream()
                        .filter(p -> inv.<Collection<Long>>getArgument(0)
                                .contains(p.getSecurityCase().getId()))
                        .toList());
        lenient().when(paymentInfoReader.findAll(anyCollection())).thenAnswer(inv -> infos);
        lenient()
                .when(paymentDecisionRepository.findByCasePaymentSecurityCaseIdIn(anyCollection()))
                .thenAnswer(inv -> decisions.stream()
                        .filter(d -> inv.<Collection<Long>>getArgument(0)
                                .contains(d.getCasePayment().getSecurityCase().getId()))
                        .toList());
    }

    // 다른 가맹점에 같은 customer_ref가 있어도 남의 고객이다 — "같은 고객"으로 나오면 안 된다
    @Test
    void similarCases_sameCustomerRefOtherMerchant_isNotSameCustomer() {
        SecurityCase current = openCase(1L, 1L, "customer-1", HIGH);
        closedCase(2L, 2L, "customer-1", 1, HIGH);
        closedCase(3L, 1L, "customer-1", 2, HIGH);

        SimilarCasesToolResponse result = finder.find(current.getId());

        assertThat(result.cases())
                .extracting(SimilarCase::caseId, SimilarCase::similarity)
                .containsExactly(tuple("3", "SAME_CUSTOMER"));
    }

    // 아직 진행 중인 사건과 지금 사건 자신은 결과가 없으니 목록에 나오지 않는다
    @Test
    void similarCases_excludesOpenAndCurrentCase() {
        SecurityCase current = openCase(1L, 1L, "customer-1", HIGH, REPEAT);
        openCase(2L, 1L, "customer-1", HIGH, REPEAT);
        closeCase(current, 1);

        SimilarCasesToolResponse result = finder.find(current.getId());

        assertThat(result.cases()).isEmpty();
        assertThat(result.totalMatched()).isZero();
    }

    // 다른 고객의 사건은 적중 규칙이 2개 이상 겹쳐야 "비슷"이다
    @Test
    void similarCases_needsTwoSharedRules() {
        SecurityCase current = openCase(1L, 1L, "customer-1", HIGH, NEW_CARD, REPEAT);
        closedCase(2L, 1L, "customer-2", 1, HIGH);
        closedCase(3L, 1L, "customer-3", 2, HIGH, REPEAT);

        SimilarCasesToolResponse result = finder.find(current.getId());

        assertThat(result.cases()).extracting(SimilarCase::caseId).containsExactly("3");
        assertThat(result.cases().getFirst().similarity()).isEqualTo("SHARED_RULES");
        assertThat(result.cases().getFirst().sharedRules())
                .extracting(SimilarCasesToolResponse.SharedRule::code)
                .containsExactly("R01", "R04");
    }

    // 같은 고객 사건이 먼저, 그다음 같은 규칙 사건 — 각각 최근 종료 순, 최대 5건이고 전체 건수는 따로 센다
    @Test
    void similarCases_sameCustomerFirstThenRecent_limitsToFive() {
        SecurityCase current = openCase(1L, 1L, "customer-1", HIGH, REPEAT);
        for (long id = 10; id < 16; id++) {
            closedCase(id, 1L, "other-" + id, (int) id, HIGH, REPEAT);
        }
        closedCase(2L, 1L, "customer-1", 1, HIGH);
        closedCase(3L, 1L, "customer-1", 3, HIGH);

        SimilarCasesToolResponse result = finder.find(current.getId());

        assertThat(result.cases()).extracting(SimilarCase::caseId).containsExactly("3", "2", "15", "14", "13");
        assertThat(result.totalMatched()).isEqualTo(8);
    }

    // 결제마다 판정이 다르면 매입 진행 · 승인 취소를 결제별 건수로 센다
    @Test
    void similarCases_mixedDecisions_countsPerPayment() {
        SecurityCase current = openCase(1L, 1L, "customer-1", HIGH);
        SecurityCase past = closedCase(2L, 1L, "customer-1", 1, HIGH);
        CasePayment second = pay(past, HIGH);
        CasePayment third = pay(past, HIGH);
        decide(
                payments.stream()
                        .filter(p -> p.getSecurityCase() == past)
                        .findFirst()
                        .orElseThrow(),
                Decision.ACCEPT);
        decide(second, Decision.ACCEPT);
        decide(third, Decision.REJECT);

        SimilarCasesToolResponse result = finder.find(current.getId());

        assertThat(result.cases()).singleElement().satisfies(c -> {
            assertThat(c.acceptCount()).isEqualTo(2);
            assertThat(c.rejectCount()).isEqualTo(1);
        });
    }

    private List<SecurityCase> closedNewestFirst() {
        return cases.stream()
                .filter(SecurityCase::isClosed)
                .sorted((a, b) -> b.getClosedAt().compareTo(a.getClosedAt()))
                .toList();
    }

    private SecurityCase openCase(Long id, Long merchantId, String customerRef, RuleHit... rules) {
        SecurityCase securityCase = SecurityCase.open(merchantId, customerRef, CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", id);
        cases.add(securityCase);
        pay(securityCase, rules);
        return securityCase;
    }

    // closedDay가 클수록 최근에 종료됐다
    private SecurityCase closedCase(Long id, Long merchantId, String customerRef, int closedDay, RuleHit... rules) {
        SecurityCase securityCase = openCase(id, merchantId, customerRef, rules);
        closeCase(securityCase, closedDay);
        return securityCase;
    }

    private void closeCase(SecurityCase securityCase, int closedDay) {
        securityCase.close(NOW.plusSeconds(closedDay * 86_400L));
    }

    private CasePayment pay(SecurityCase securityCase, RuleHit... rules) {
        CasePayment payment = CasePayment.link(securityCase, nextPaymentId++, NOW);
        payments.add(payment);
        infos.put(
                payment.getPaymentId(),
                new PaymentInfo(payment.getPaymentId(), 100_000L, "HELD", NOW, 60, "REVIEW", List.of(rules)));
        return payment;
    }

    private void decide(CasePayment payment, Decision decision) {
        decisions.add(PaymentDecision.confirm(payment, 9L, null, decision, "사유", 1, NOW));
    }
}
