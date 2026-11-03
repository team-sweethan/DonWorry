package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse.StatusCount;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// 고객 최근 결제 고르기 규칙 (#338) — P5 조회는 목으로 바꿔 사건 결제 빼기 · 건수 세기 · 자르기만 본다
// 쿼리 자체(가맹점 · 고객 · 기간 · 최근 순)는 PaymentReaderMariaDbTests 몫이라 목은 쿼리 조건대로 걸러 돌려준다
@ExtendWith(MockitoExtension.class)
class PaymentHistoryFinderTests {

    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    static final long MERCHANT = 1L;
    static final String CUSTOMER = "customer-1";

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CasePaymentRepository casePaymentRepository;

    @Mock
    PaymentReader paymentReader;

    final List<PaymentSummary> stored = new ArrayList<>();
    final List<Long> casePaymentIds = new ArrayList<>();
    long nextPaymentId = 100;

    PaymentHistoryFinder finder;

    @BeforeEach
    void setUp() {
        finder = new PaymentHistoryFinder(securityCaseRepository, casePaymentRepository, paymentReader);
        SecurityCase securityCase = SecurityCase.open(MERCHANT, CUSTOMER, CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenAnswer(inv -> casePaymentIds.stream()
                        .map(id -> CasePayment.link(securityCase, id, NOW))
                        .toList());
        // P5 쿼리처럼 뺄 결제를 빼고 since 이후를 최근 순으로 limit건 — 가맹점 · 고객 조건은 목 데이터가 모두 같은 고객이라 생략
        when(paymentReader.findRecentByCustomer(anyLong(), anyString(), any(), anyCollection(), anyInt()))
                .thenAnswer(inv -> {
                    Instant since = inv.getArgument(2);
                    Collection<Long> excluded = inv.getArgument(3);
                    int limit = inv.getArgument(4);
                    return stored.stream()
                            .filter(p -> !excluded.contains(p.paymentId()))
                            .filter(p -> !p.requestedAt().isBefore(since))
                            .sorted(Comparator.comparing(PaymentSummary::requestedAt)
                                    .reversed())
                            .limit(limit)
                            .toList();
                });
    }

    // 이 사건 결제가 "평소 결제"로 섞이면 사건 자체가 정상 근거처럼 보인다
    @Test
    void paymentHistory_excludesCasePayments() {
        long casePayment = payment("HELD", 1);
        casePaymentIds.add(casePayment);
        long before = payment("CAPTURED", 5);

        PaymentHistoryToolResponse history = finder.find(7L, NOW);

        assertThat(history.payments())
                .extracting(PaymentHistoryToolResponse.Payment::paymentId)
                .containsExactly(String.valueOf(before));
        assertThat(history.statusCounts()).containsExactly(new StatusCount("CAPTURED", 1));
    }

    // 상태별 건수는 코드가 센다 — 매입 완료가 먼저, 모르는 상태는 맨 뒤
    @Test
    void paymentHistory_countsByStatus() {
        payment("VOIDED", 1);
        payment("CAPTURED", 2);
        payment("SOMETHING_NEW", 3);
        payment("CAPTURED", 4);
        payment("DECLINED", 5);

        PaymentHistoryToolResponse history = finder.find(7L, NOW);

        assertThat(history.statusCounts())
                .extracting(StatusCount::paymentStatus, StatusCount::count)
                .containsExactly(
                        tuple("CAPTURED", 2), tuple("VOIDED", 1), tuple("DECLINED", 1), tuple("SOMETHING_NEW", 1));
        assertThat(history.truncated()).isFalse();
    }

    // 30일보다 오래된 결제는 빼고, 사건 밖 결제가 20건을 넘으면 20건만 두고 잘렸다고 표시한다
    @Test
    void paymentHistory_olderThan30Days_excluded_over20_marksTruncated() {
        long old = payment("CAPTURED", 31);
        casePaymentIds.add(payment("HELD", 0));
        for (int day = 1; day <= 21; day++) {
            payment("CAPTURED", day);
        }

        PaymentHistoryToolResponse history = finder.find(7L, NOW);

        assertThat(history.lookbackDays()).isEqualTo(30);
        assertThat(history.payments()).hasSize(20);
        assertThat(history.payments())
                .extracting(PaymentHistoryToolResponse.Payment::paymentId)
                .doesNotContain(String.valueOf(old));
        assertThat(history.truncated()).isTrue();
        assertThat(history.statusCounts()).containsExactly(new StatusCount("CAPTURED", 20));
    }

    // 사건에 결제가 많이 묶여도(조회 상한보다 많아도) 그 전 사건 밖 결제를 놓치지 않는다 (#338 리뷰)
    @Test
    void paymentHistory_manyCasePayments_stillFindsOlderPayments() {
        long before = payment("CAPTURED", 5);
        for (int i = 0; i < 100; i++) {
            casePaymentIds.add(payment("HELD", 0));
        }

        PaymentHistoryToolResponse history = finder.find(7L, NOW);

        assertThat(history.payments())
                .extracting(PaymentHistoryToolResponse.Payment::paymentId)
                .containsExactly(String.valueOf(before));
        assertThat(history.truncated()).isFalse();
    }

    // daysAgo일 전(같은 날이면 나중에 만든 결제가 더 최근)에 저장된 결제
    private long payment(String status, int daysAgo) {
        long id = nextPaymentId++;
        Instant at = NOW.minus(daysAgo, ChronoUnit.DAYS).plusSeconds(id);
        stored.add(new PaymentSummary(id, 100_000L, status, at));
        return id;
    }
}
