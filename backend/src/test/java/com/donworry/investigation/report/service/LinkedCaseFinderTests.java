package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsPaymentMatchReader;
import com.donworry.fds.api.FdsPaymentMatches;
import com.donworry.fds.api.FdsPaymentMatches.Kind;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.LinkedCasesToolResponse;
import com.donworry.investigation.report.dto.LinkedCasesToolResponse.CasePaymentMatch;
import com.donworry.investigation.report.dto.LinkedCasesToolResponse.LinkedCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

// 같은 카드 · 기기 · IP로 열린 지난 사건 Tool 응답 (#821)
@ExtendWith(MockitoExtension.class)
class LinkedCaseFinderTests {

    static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    static final Instant CLOSED_AT = Instant.parse("2026-11-10T03:00:00Z");

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CasePaymentRepository casePaymentRepository;

    @Mock
    PaymentDecisionRepository paymentDecisionRepository;

    @Mock
    FdsPaymentMatchReader fdsPaymentMatchReader;

    // 지난 사건의 판정은 같은 카드인 결제에 내린 판정만 센다 — 다른 결제 판정 · 판정 사유 · 고객 번호는 싣지 않는다
    @Test
    void find_sameCardClosedCase_returnsMatchedPaymentDecision() {
        SecurityCase current = securityCase(7L, "customer-1");
        SecurityCase past = securityCase(20L, "customer-2");
        past.close(CLOSED_AT);
        CasePayment matched = CasePayment.link(past, 201L, CLOSED_AT);
        CasePayment other = CasePayment.link(past, 202L, CLOSED_AT);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(current));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(current, 1L, NOW)));
        when(securityCaseRepository
                        .findTop100ByMerchantIdAndCustomerRefNotAndStatusAndClosedAtGreaterThanEqualOrderByClosedAtDesc(
                                eq(1L), eq("customer-1"), eq(CaseStatus.CLOSED), any()))
                .thenReturn(List.of(past));
        when(casePaymentRepository.findBySecurityCaseIdIn(List.of(20L))).thenReturn(List.of(matched, other));
        when(fdsPaymentMatchReader.findMatching(eq(1L), eq(List.of(1L)), anyCollection()))
                .thenReturn(new FdsPaymentMatches(
                        new Kind(true, Set.of(201L)), new Kind(true, Set.of()), Kind.notProvided()));
        when(paymentDecisionRepository.findByCasePaymentSecurityCaseIdIn(List.of(20L)))
                .thenReturn(List.of(
                        PaymentDecision.confirm(matched, 3L, null, Decision.REJECT, "직원 메모 원문", 1, CLOSED_AT),
                        PaymentDecision.confirm(other, 3L, null, Decision.ACCEPT, "직원 메모 원문", 1, CLOSED_AT)));

        LinkedCasesToolResponse response = finder().find(7L, "CARD_DEVICE", NOW);

        assertThat(response.cases())
                .containsExactly(LinkedCase.builder()
                        .caseId("20")
                        .matchedBy(List.of("CARD"))
                        .closedAt(CLOSED_AT)
                        .paymentCount(1)
                        .acceptCount(0)
                        .rejectCount(1)
                        .casePayments(List.of(new CasePaymentMatch("1", List.of("CARD"))))
                        .build());
        assertThat(response.totalMatched()).isEqualTo(1);
        assertThat(response.limited()).isFalse();
        assertThat(response.notProvided()).isEmpty();
        assertThat(response.toString()).doesNotContain("직원 메모 원문", "customer-2");
    }

    // 이번 사건 결제가 여러 건이면 지난 사건마다 어느 결제와 겹쳤는지 남긴다 (#818 Codex 리뷰)
    // 없으면 결제 B만 겹친 지난 사건이 결제 A의 같은 카드 의심 카드에 붙는다
    @Test
    void find_twoCasePayments_recordsWhichPaymentMatched() {
        SecurityCase current = securityCase(7L, "customer-1");
        SecurityCase past = securityCase(20L, "customer-2");
        past.close(CLOSED_AT);
        CasePayment pastPayment = CasePayment.link(past, 201L, CLOSED_AT);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(current));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(current, 1L, NOW), CasePayment.link(current, 2L, NOW)));
        when(securityCaseRepository
                        .findTop100ByMerchantIdAndCustomerRefNotAndStatusAndClosedAtGreaterThanEqualOrderByClosedAtDesc(
                                eq(1L), eq("customer-1"), eq(CaseStatus.CLOSED), any()))
                .thenReturn(List.of(past));
        when(casePaymentRepository.findBySecurityCaseIdIn(List.of(20L))).thenReturn(List.of(pastPayment));
        when(fdsPaymentMatchReader.findMatching(eq(1L), eq(List.of(1L)), anyCollection()))
                .thenReturn(
                        new FdsPaymentMatches(new Kind(true, Set.of()), new Kind(true, Set.of()), Kind.notProvided()));
        when(fdsPaymentMatchReader.findMatching(eq(1L), eq(List.of(2L)), anyCollection()))
                .thenReturn(new FdsPaymentMatches(
                        new Kind(true, Set.of(201L)), new Kind(true, Set.of()), Kind.notProvided()));
        when(paymentDecisionRepository.findByCasePaymentSecurityCaseIdIn(List.of(20L)))
                .thenReturn(
                        List.of(PaymentDecision.confirm(pastPayment, 3L, null, Decision.REJECT, "메모", 1, CLOSED_AT)));

        LinkedCasesToolResponse response = finder().find(7L, "CARD_DEVICE", NOW);

        assertThat(response.cases())
                .singleElement()
                .extracting(LinkedCase::casePayments)
                .isEqualTo(List.of(new CasePaymentMatch("2", List.of("CARD"))));
    }

    // 후보가 상한을 넘어 일부만 비교했으면 limited — "없음"이 확인된 것처럼 보이지 않게 (#818 Codex 리뷰)
    // 결제가 너무 많은 사건 하나 때문에 나머지 사건까지 비교를 멈추지 않는다
    @Test
    void find_tooManyCandidates_marksLimited() {
        SecurityCase current = securityCase(7L, "customer-1");
        SecurityCase huge = securityCase(20L, "customer-2");
        SecurityCase older = securityCase(21L, "customer-3");
        huge.close(CLOSED_AT);
        older.close(CLOSED_AT.minusSeconds(3_600));
        List<CasePayment> hugePayments = LongStream.rangeClosed(1, FdsPaymentMatchReader.MAX_CANDIDATES + 1)
                .mapToObj(i -> CasePayment.link(huge, 10_000L + i, CLOSED_AT))
                .toList();
        CasePayment olderPayment = CasePayment.link(older, 301L, CLOSED_AT);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(current));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(current, 1L, NOW)));
        when(securityCaseRepository
                        .findTop100ByMerchantIdAndCustomerRefNotAndStatusAndClosedAtGreaterThanEqualOrderByClosedAtDesc(
                                eq(1L), eq("customer-1"), eq(CaseStatus.CLOSED), any()))
                .thenReturn(List.of(huge, older));
        when(casePaymentRepository.findBySecurityCaseIdIn(List.of(20L, 21L)))
                .thenReturn(Stream.concat(hugePayments.stream(), Stream.of(olderPayment))
                        .toList());
        when(fdsPaymentMatchReader.findMatching(eq(1L), eq(List.of(1L)), eq(Set.of(301L))))
                .thenReturn(new FdsPaymentMatches(
                        new Kind(true, Set.of(301L)), new Kind(true, Set.of()), Kind.notProvided()));
        when(paymentDecisionRepository.findByCasePaymentSecurityCaseIdIn(List.of(21L)))
                .thenReturn(List.of());

        LinkedCasesToolResponse response = finder().find(7L, "CARD_DEVICE", NOW);

        assertThat(response.limited()).isTrue();
        assertThat(response.cases()).extracting(LinkedCase::caseId).containsExactly("21");
    }

    // 허용 밖 조회 범위는 사건 · 결제를 읽기 전에 거절한다 — AI가 지어낸 범위로 조회하지 않는다
    @Test
    void find_unknownBy_returns400() {
        assertThatThrownBy(() -> finder().find(7L, "ALL", NOW))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> finder().find(7L, null, NOW)).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(securityCaseRepository, casePaymentRepository, fdsPaymentMatchReader);
    }

    private LinkedCaseFinder finder() {
        return new LinkedCaseFinder(
                securityCaseRepository, casePaymentRepository, paymentDecisionRepository, fdsPaymentMatchReader);
    }

    private static SecurityCase securityCase(Long id, String customerRef) {
        SecurityCase securityCase =
                SecurityCase.open(1L, customerRef, CaseType.PAYMENT_FRAUD, NOW.minusSeconds(86_400));
        ReflectionTestUtils.setField(securityCase, "id", id);
        return securityCase;
    }
}
