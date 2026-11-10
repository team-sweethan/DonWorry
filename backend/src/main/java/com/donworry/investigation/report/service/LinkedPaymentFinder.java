package com.donworry.investigation.report.service;

import com.donworry.fds.api.FdsLinkedPaymentReader;
import com.donworry.fds.api.FdsLinkedPayments;
import com.donworry.fds.api.FdsResult;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Match;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Nearby;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.PaymentLinks;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.ResultCount;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 (#352) — AI Tool과 AI를 끈 고정 보고서가 같이 쓴다
// P2 공개 창구를 직접 읽는다 (#346 PaymentSignalReader와 같은 경로, P3 결제 정보 창구는 거치지 않음)
// 비교 · 집계는 P2가 원문으로 하고, 여기서는 사건의 가맹점 · 고객 · 결제를 넘기고 숫자만 받는다
@Component
@RequiredArgsConstructor
public class LinkedPaymentFinder {

    static final String SOURCE = "P2";
    static final int LOOKBACK_DAYS = 30;
    // 30일 중 사건 결제 앞뒤 이 시간 안의 결제를 따로 센다 (#637) — 출발값, 판정 기준표 개선 때 측정해 조정한다
    static final Duration NEARBY_WINDOW = Duration.ofHours(1);
    // 사건 질문 · 답변의 넓혀 보기 (ADR-0086, #751) — 1시간은 보고서 기본, 6 · 24시간은 AI가 고른다
    // 그 밖의 값은 받지 않는다 (넓은 시간대를 받으면 조회 계약이 흐려진다)
    static final Set<Integer> ALLOWED_NEARBY_HOURS = Set.of(1, 6, 24);

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final FdsLinkedPaymentReader fdsLinkedPaymentReader;

    // 보고서 기본 조회 · 고정 보고서 — 사건 결제 앞뒤 1시간
    // 같은 클래스 안 호출은 프록시를 거치지 않아 여기에도 트랜잭션을 둔다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public LinkedPaymentsToolResponse find(Long caseId, Instant now) {
        return find(caseId, now, (int) NEARBY_WINDOW.toHours());
    }

    // 별도 읽기 트랜잭션 — 고정 보고서가 이 조회 실패를 잡아 넘겨도 보고서 트랜잭션이 되돌림 전용이 되지 않게 한다
    // 허용 밖 시간은 사건을 읽기 전에 400으로 거절한다 (PaymentHistoryFinder 기간 검사와 같다)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public LinkedPaymentsToolResponse find(Long caseId, Instant now, int nearbyHours) {
        if (!ALLOWED_NEARBY_HOURS.contains(nearbyHours)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "앞뒤 시간은 1 · 6 · 24시간만 됩니다.");
        }
        Duration nearbyWindow = Duration.ofHours(nearbyHours);
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
                now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS),
                nearbyWindow);
        return LinkedPaymentsToolResponse.builder()
                .caseId(String.valueOf(caseId))
                .evidenceVersion(securityCase.getEvidenceVersion())
                .source(SOURCE)
                .retrievedAt(now)
                .lookbackDays(LOOKBACK_DAYS)
                .nearbyWindowMinutes((int) nearbyWindow.toMinutes())
                .card(match(linked.card()))
                .device(match(linked.device()))
                .ip(match(linked.ip()))
                .payments(linked.payments().stream()
                        .map(p -> new PaymentLinks(
                                String.valueOf(p.paymentId()), match(p.card()), match(p.device()), match(p.ip())))
                        .toList())
                .build();
    }

    // 판정별 건수는 FDS 판정 순서로 늘어놓고 0건은 뺀다
    private static Match match(FdsLinkedPayments.Match m) {
        FdsLinkedPayments.Nearby near = m.nearby();
        return Match.builder()
                .provided(m.provided())
                .otherCustomerCount(m.otherCustomerCount())
                .paymentCount(m.paymentCount())
                .resultCounts(resultCounts(m.resultCounts()))
                .declinedCount(m.declinedCount())
                .nearby(Nearby.builder()
                        .otherCustomerCount(near.otherCustomerCount())
                        .paymentCount(near.paymentCount())
                        .resultCounts(resultCounts(near.resultCounts()))
                        .declinedCount(near.declinedCount())
                        .build())
                .build();
    }

    private static List<ResultCount> resultCounts(Map<FdsResult, Integer> counts) {
        return Arrays.stream(FdsResult.values())
                .filter(result -> counts.getOrDefault(result, 0) > 0)
                .map(result -> new ResultCount(result.name(), counts.get(result)))
                .toList();
    }
}
