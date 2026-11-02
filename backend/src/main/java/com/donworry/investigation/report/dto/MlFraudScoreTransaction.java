package com.donworry.investigation.report.dto;

import com.donworry.fds.api.FdsMlTransaction;
import lombok.Builder;

// ai-server MlTransaction 한 건 (app/schemas/ml.py와 같은 JSON 이름, #414)
// 고객 · 기기 · 카드 값은 P2가 준 별칭 그대로 보낸다 — 원문은 P2 밖으로 나가지 않는다 (ADR-0062)
@Builder
public record MlFraudScoreTransaction(
        Long paymentId,
        Long merchantId,
        String customerRef,
        long amount,
        // ai-server는 시간대가 붙은 시각만 받는다 — Instant.toString()은 항상 Z로 끝난다 (AiInvestigationRequest 선례)
        String paymentRequestedAt,
        String deviceId,
        String cardToken) {

    public static MlFraudScoreTransaction from(FdsMlTransaction transaction) {
        return MlFraudScoreTransaction.builder()
                .paymentId(transaction.paymentId())
                .merchantId(transaction.merchantId())
                .customerRef(transaction.customerKey())
                .amount(transaction.amount())
                .paymentRequestedAt(transaction.paymentRequestedAt().toString())
                .deviceId(transaction.deviceKey())
                .cardToken(transaction.cardKey())
                .build();
    }
}
