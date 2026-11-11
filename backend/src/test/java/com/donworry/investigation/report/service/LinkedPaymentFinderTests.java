package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsLinkedPaymentReader;
import com.donworry.fds.api.FdsLinkedPayments;
import com.donworry.fds.api.FdsLinkedPayments.PaymentMatch;
import com.donworry.fds.api.FdsResult;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Nearby;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.PaymentLinks;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.ResultCount;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

// 같은 카드 · 기기 · IP 결제 Tool 응답 — P2 집계를 사건 전체와 결제별로 옮겨 담는다 (#352, #533)
@ExtendWith(MockitoExtension.class)
class LinkedPaymentFinderTests {

    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CasePaymentRepository casePaymentRepository;

    @Mock
    FdsLinkedPaymentReader fdsLinkedPaymentReader;

    // 결제 1만 다른 고객과 카드가 겹치면 결제별 칸에서 결제 1에만 건수가 있다 (#533)
    @Test
    void find_twoCasePayments_returnsLinksPerPayment() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 1L, NOW), CasePayment.link(securityCase, 2L, NOW)));
        FdsLinkedPayments.Match shared = FdsLinkedPayments.Match.builder()
                .provided(true)
                .otherCustomerCount(1)
                .paymentCount(2)
                .resultCounts(Map.of(FdsResult.DENY, 1, FdsResult.REVIEW, 1))
                .declinedCount(1)
                .nearby(FdsLinkedPayments.Nearby.builder()
                        .otherCustomerCount(1)
                        .paymentCount(1)
                        .resultCounts(Map.of(FdsResult.DENY, 1))
                        .declinedCount(1)
                        .build())
                .build();
        FdsLinkedPayments.Match none = FdsLinkedPayments.Match.builder()
                .provided(true)
                .otherCustomerCount(0)
                .paymentCount(0)
                .resultCounts(Map.of())
                .declinedCount(0)
                .nearby(FdsLinkedPayments.Nearby.none())
                .build();
        FdsLinkedPayments.Match notProvided = FdsLinkedPayments.Match.notProvided();
        when(fdsLinkedPaymentReader.findLinked(
                        eq(1L), eq("customer-1"), eq(List.of(1L, 2L)), any(), eq(Duration.ofHours(1))))
                .thenReturn(new FdsLinkedPayments(
                        shared,
                        none,
                        notProvided,
                        List.of(
                                new PaymentMatch(1L, shared, none, notProvided),
                                new PaymentMatch(2L, none, none, notProvided))));

        var response = new LinkedPaymentFinder(securityCaseRepository, casePaymentRepository, fdsLinkedPaymentReader)
                .find(7L, NOW);

        assertThat(response.card().paymentCount()).isEqualTo(2);
        assertThat(response.payments())
                .extracting(PaymentLinks::paymentId, p -> p.card().paymentCount(), p -> p.ip().provided())
                .containsExactly(tuple("1", 2, false), tuple("2", 0, false));
        // 판정별 건수는 사건 합계와 같은 순서(허용 · 검토 · 차단 · 평가 오류)로 옮긴다
        assertThat(response.payments().get(0).card().resultCounts())
                .extracting(r -> r.result())
                .containsExactly("REVIEW", "DENY");
        // 사건 결제 앞뒤 1시간 숫자와 그 시간을 그대로 옮긴다 (#637)
        assertThat(response.nearbyWindowMinutes()).isEqualTo(60);
        assertThat(response.card().nearby())
                .isEqualTo(Nearby.builder()
                        .otherCustomerCount(1)
                        .paymentCount(1)
                        .resultCounts(List.of(new ResultCount("DENY", 1)))
                        .declinedCount(1)
                        .build());
        assertThat(response.payments().get(1).card().nearby().paymentCount()).isZero();
    }

    // 질문 넓혀 보기 24시간 (#751) — P2에 24시간을 넘기고 응답에도 그 시간을 적는다
    // ai-server는 응답 시간으로 "요청한 범위의 값인지" 확인한다 (다르면 조회 실패로 버림)
    @Test
    void find_nearbyHours24_passesWindowAndReportsMinutes() {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(CasePayment.link(securityCase, 1L, NOW)));
        FdsLinkedPayments.Match notProvided = FdsLinkedPayments.Match.notProvided();
        when(fdsLinkedPaymentReader.findLinked(
                        eq(1L), eq("customer-1"), eq(List.of(1L)), any(), eq(Duration.ofHours(24))))
                .thenReturn(new FdsLinkedPayments(notProvided, notProvided, notProvided, List.of()));

        var response = new LinkedPaymentFinder(securityCaseRepository, casePaymentRepository, fdsLinkedPaymentReader)
                .find(7L, NOW, 24);

        assertThat(response.nearbyWindowMinutes()).isEqualTo(24 * 60);
    }

    // 1 · 6 · 24시간 밖은 사건 · 결제를 읽기 전에 400으로 거절한다 — 계약 밖 시간으로 조회하지 않게 (#751)
    @ParameterizedTest
    @ValueSource(ints = {0, 2, 48})
    void find_otherNearbyHours_rejectedBeforeReading(int hours) {
        var finder = new LinkedPaymentFinder(securityCaseRepository, casePaymentRepository, fdsLinkedPaymentReader);

        assertThatThrownBy(() -> finder.find(7L, NOW, hours))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(securityCaseRepository, casePaymentRepository, fdsLinkedPaymentReader);
    }
}
