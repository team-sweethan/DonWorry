package com.donworry.investigation.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 화면 · 보고서에 보여 줄 P5 결제 정보와 P2 FDS 평가 근거 — P3는 금액 · 상태를 저장하지 않고 조회만 한다
// 지금은 공개 계약이 없어 목 구현(MockPaymentInfoStore)을 쓴다. P5 · P2 api가 생기면 구현만 바꾼다
public interface PaymentInfoReader {

    Optional<PaymentInfo> find(Long paymentId);

    record PaymentInfo(
            Long paymentId,
            Long amount,
            String paymentStatus,
            Instant requestedAt,
            Integer riskScore,
            String fdsResult,
            List<String> ruleHits) {

        public PaymentInfo {
            ruleHits = ruleHits == null ? List.of() : List.copyOf(ruleHits);
        }
    }
}
