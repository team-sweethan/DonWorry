package com.donworry.investigation.service;

import com.donworry.fds.api.FdsEvidenceReader;
import com.donworry.fds.api.FdsPaymentEvidence;
import com.donworry.investigation.service.mock.MockPaymentInfoStore;
import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// P5 결제 정보(ADR-0026)와 P2 FDS 평가 근거(ADR-0034)를 결제별로 합친다 (#163)
// 시연 결제(P5 payments에 없음)만 목 저장소에서 읽는다 — 시연 API와 함께 이 분기도 지운다 (#163 2단계)
@Component
@RequiredArgsConstructor
class PaymentFdsInfoReader implements PaymentInfoReader {

    private final PaymentReader paymentReader;
    private final FdsEvidenceReader fdsEvidenceReader;
    private final MockPaymentInfoStore mockPaymentInfoStore;

    @Override
    public Map<Long, PaymentInfo> findAll(Collection<Long> paymentIds) {
        Map<Long, PaymentInfo> found = new LinkedHashMap<>();
        List<Long> realIds = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(paymentIds)) {
            Optional<PaymentInfo> demo = mockPaymentInfoStore.find(id);
            if (demo.isPresent()) {
                found.put(id, demo.get());
            } else {
                realIds.add(id);
            }
        }
        if (realIds.isEmpty()) {
            return found;
        }
        // P2는 평가가 있는 결제만 돌려준다. 빠진 결제는 평가 없음이다
        Map<Long, FdsPaymentEvidence> evidences = fdsEvidenceReader.findLatestByPaymentIds(realIds).stream()
                .collect(Collectors.toMap(FdsPaymentEvidence::paymentId, Function.identity()));
        for (Long id : realIds) {
            Optional<PaymentSummary> payment = paymentReader.findById(id);
            FdsPaymentEvidence evidence = evidences.get(id);
            if (payment.isPresent() || evidence != null) {
                found.put(id, info(id, payment, evidence));
            }
        }
        return found;
    }

    // 한쪽을 못 찾으면 그쪽 값만 null로 둔다 — 추정값을 채우지 않는다 (ADR-0019)
    // 평가가 모두 ERROR면 P2가 최신 ERROR를 준다: 점수 null · 판정 ERROR · 적중 룰 없음
    private static PaymentInfo info(Long id, Optional<PaymentSummary> payment, FdsPaymentEvidence evidence) {
        return new PaymentInfo(
                id,
                payment.map(PaymentSummary::amount).orElse(null),
                payment.map(PaymentSummary::paymentStatus).orElse(null),
                payment.map(PaymentSummary::requestedAt).orElse(null),
                evidence == null ? null : evidence.riskScore(),
                evidence == null ? null : evidence.result().name(),
                evidence == null
                        ? List.of()
                        : evidence.matchedRules().stream()
                                .map(rule -> new RuleHit(rule.ruleCode(), rule.ruleName(), rule.appliedScore()))
                                .toList());
    }
}
