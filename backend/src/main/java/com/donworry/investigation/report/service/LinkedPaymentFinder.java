package com.donworry.investigation.report.service;

import com.donworry.fds.api.FdsLinkedPaymentReader;
import com.donworry.fds.api.FdsLinkedPayments;
import com.donworry.fds.api.FdsResult;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Match;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.ResultCount;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 (#352) — AI Tool과 AI를 끈 고정 보고서가 같이 쓴다
// P2 공개 창구를 직접 읽는다 (#346 PaymentSignalReader와 같은 경로, P3 결제 정보 창구는 거치지 않음)
// 비교 · 집계는 P2가 원문으로 하고, 여기서는 사건의 가맹점 · 고객 · 결제를 넘기고 숫자만 받는다
@Component
@RequiredArgsConstructor
public class LinkedPaymentFinder {

    static final String SOURCE = "P2";
    static final int LOOKBACK_DAYS = 30;

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final FdsLinkedPaymentReader fdsLinkedPaymentReader;

    // 별도 읽기 트랜잭션 — 고정 보고서가 이 조회 실패를 잡아 넘겨도 보고서 트랜잭션이 되돌림 전용이 되지 않게 한다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public LinkedPaymentsToolResponse find(Long caseId, Instant now) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        List<Long> casePaymentIds = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                .map(CasePayment::getPaymentId)
                .toList();
        FdsLinkedPayments linked = fdsLinkedPaymentReader.findLinked(
                securityCase.getMerchantId(),
                securityCase.getCustomerRef(),
                casePaymentIds,
                now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS));
        return LinkedPaymentsToolResponse.builder()
                .caseId(String.valueOf(caseId))
                .evidenceVersion(securityCase.getEvidenceVersion())
                .source(SOURCE)
                .retrievedAt(now)
                .lookbackDays(LOOKBACK_DAYS)
                .card(match(linked.card()))
                .device(match(linked.device()))
                .ip(match(linked.ip()))
                .build();
    }

    // 판정별 건수는 FDS 판정 순서로 늘어놓고 0건은 뺀다
    private static Match match(FdsLinkedPayments.Match m) {
        List<ResultCount> counts = Arrays.stream(FdsResult.values())
                .filter(result -> m.resultCounts().getOrDefault(result, 0) > 0)
                .map(result -> new ResultCount(result.name(), m.resultCounts().get(result)))
                .toList();
        return Match.builder()
                .provided(m.provided())
                .otherCustomerCount(m.otherCustomerCount())
                .paymentCount(m.paymentCount())
                .resultCounts(counts)
                .declinedCount(m.declinedCount())
                .build();
    }
}
