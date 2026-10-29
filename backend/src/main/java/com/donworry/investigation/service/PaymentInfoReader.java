package com.donworry.investigation.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 화면 · 보고서에 보여 줄 P5 결제 정보와 P2 FDS 평가 근거 — P3는 금액 · 상태를 저장하지 않고 조회만 한다
// 구현은 PaymentFdsInfoReader(P5 PaymentReader + P2 FdsEvidenceReader). 시연 결제만 목 저장소에서 읽는다 (#163)
public interface PaymentInfoReader {

    // P5 · P2 어디에도 없는 결제는 결과에서 빠진다. 한쪽만 있으면 다른 쪽 값은 null이다
    // 조회 오류는 null로 숨기지 않고 그대로 던진다 — 장애가 "값 모름"으로 보이면 빈 근거로 판정할 수 있다
    Map<Long, PaymentInfo> findAll(Collection<Long> paymentIds);

    default Optional<PaymentInfo> find(Long paymentId) {
        return Optional.ofNullable(findAll(List.of(paymentId)).get(paymentId));
    }

    // amount · paymentStatus · requestedAt은 P5, riskScore · fdsResult · ruleHits는 P2 값이다
    record PaymentInfo(
            Long paymentId,
            Long amount,
            String paymentStatus,
            Instant requestedAt,
            Integer riskScore,
            String fdsResult,
            List<RuleHit> ruleHits) {

        public PaymentInfo {
            ruleHits = ruleHits == null ? List.of() : List.copyOf(ruleHits);
        }
    }

    // P2 MATCH 적중 룰 — 이름은 평가 당시 이름, appliedScore는 이 룰이 점수에 더한 값
    // thresholdSnapshot · observedValue는 P2 기준값 · 실제값 JSON 원본 (#326). 식별자가 들 수 있어 화면에 그대로 내보내지 않는다
    record RuleHit(String code, String name, Integer appliedScore, String thresholdSnapshot, String observedValue) {

        // 기준값 · 실제값이 없는 시연 결제 · 테스트용
        public RuleHit(String code, String name, Integer appliedScore) {
            this(code, name, appliedScore, null, null);
        }
    }
}
