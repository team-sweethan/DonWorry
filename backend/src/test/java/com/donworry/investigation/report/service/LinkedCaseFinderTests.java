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
import com.donworry.investigation.report.dto.LinkedCasesToolResponse.LinkedCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                        .build());
        assertThat(response.totalMatched()).isEqualTo(1);
        assertThat(response.notProvided()).isEmpty();
        assertThat(response.toString()).doesNotContain("직원 메모 원문", "customer-2");
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
