package com.donworry.settlement.service;

import com.donworry.settlement.repository.*;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class SettlementCompletionTransactionService {
    private final SettlementRepository settlements;
    private final CaptureRepository captures;
    private final OrderFulfillmentRepository fulfillments;
    private final SettlementCompletionPolicy policy;

    // 픽업 수신과 동일하게 주문 → 정산 순서로 잠가 역순 잠금에 의한 교착을 피한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String complete(Long settlementId, Long merchantId, String orderId, Instant cutoff) {
        if (settlementId == null
                || settlementId <= 0
                || merchantId == null
                || merchantId <= 0
                || orderId == null
                || orderId.isBlank()
                || cutoff == null) throw new IllegalArgumentException("정산 완료 대상과 기준 시각이 필요합니다.");
        var fulfillment = fulfillments.findForUpdate(merchantId, orderId).orElse(null);
        var settlement = settlements.findForCompletion(settlementId).orElse(null);
        if (settlement == null) return "MISSING_SETTLEMENT";
        if (!merchantId.equals(settlement.getMerchantId())) return "MERCHANT_MISMATCH";
        var capture = captures.findById(settlement.getCaptureId()).orElse(null);
        var reason = policy.evaluate(settlement, capture, fulfillment, cutoff);
        if (!"ELIGIBLE".equals(reason)) return reason;
        settlement.complete(Instant.now());
        return "COMPLETED";
    }
}
