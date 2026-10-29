package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse.StatusCount;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 고객 최근 결제 찾기 (#338) — AI Tool과 AI를 끈 고정 보고서가 같이 쓴다. P5 결제는 공개 창구로 읽기만 한다
// 같은 고객 = 같은 가맹점 + customer_ref (customer_ref는 가맹점마다 따로라 SimilarCaseFinder와 같은 기준)
// 이 사건 결제는 뺀다 — "평소 결제"와 비교하는 것이 목적이다. 상태별 건수는 코드가 센다
@Component
@RequiredArgsConstructor
public class PaymentHistoryFinder {

    static final String SOURCE = "P5";
    static final int LOOKBACK_DAYS = 30;
    static final int MAX_PAYMENTS = 20;
    // P5 PaymentReader 한 번 조회 상한과 같다
    static final int MAX_READ = 100;
    // 화면 · 보고서에 상태를 늘어놓는 순서 — 모르는 상태는 맨 뒤에 나온 순서대로
    static final List<String> STATUS_ORDER =
            List.of("CAPTURED", "HELD", "PENDING", "VOIDED", "DECLINED", "DENIED", "EXPIRED");

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentReader paymentReader;

    // 별도 읽기 트랜잭션 — 고정 보고서가 이 조회 실패를 잡아 넘겨도 보고서 트랜잭션이 되돌림 전용이 되지 않게 한다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentHistoryToolResponse find(Long caseId, Instant now) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        Set<Long> casePaymentIds = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                .map(CasePayment::getPaymentId)
                .collect(Collectors.toSet());
        // 사건 결제를 빼고도 상한을 넘는지 알 수 있게 사건 결제 수 + 1건 더 읽는다
        int limit = Math.min(MAX_PAYMENTS + casePaymentIds.size() + 1, MAX_READ);
        List<PaymentSummary> others = paymentReader
                .findRecentByCustomer(
                        securityCase.getMerchantId(),
                        securityCase.getCustomerRef(),
                        now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS),
                        limit)
                .stream()
                .filter(payment -> !casePaymentIds.contains(payment.paymentId()))
                .toList();
        List<PaymentSummary> shown = others.stream().limit(MAX_PAYMENTS).toList();
        return new PaymentHistoryToolResponse(
                String.valueOf(caseId),
                securityCase.getEvidenceVersion(),
                SOURCE,
                now,
                LOOKBACK_DAYS,
                shown.stream()
                        .map(p -> new PaymentHistoryToolResponse.Payment(
                                String.valueOf(p.paymentId()), p.amount(), p.paymentStatus(), p.requestedAt()))
                        .toList(),
                statusCounts(shown),
                others.size() > MAX_PAYMENTS);
    }

    private static List<StatusCount> statusCounts(List<PaymentSummary> payments) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        payments.forEach(payment -> counts.merge(payment.paymentStatus(), 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> statusOrder(entry.getKey())))
                .map(entry -> new StatusCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static int statusOrder(String status) {
        int index = STATUS_ORDER.indexOf(status);
        return index < 0 ? STATUS_ORDER.size() : index;
    }
}
