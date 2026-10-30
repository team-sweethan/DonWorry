package com.donworry.settlement.service;

import com.donworry.settlement.entity.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 취소 연동 전 범위의 완료 조건이다. 취소 동시 실행 보장은 후속 계약에서 확장한다. */
@Component
public class SettlementCompletionPolicy {
    public String evaluate(Settlement s, Capture c, OrderFulfillment f, Instant now) {
        if (s.getStatus() != SettlementStatus.SCHEDULED) return "NOT_SCHEDULED";
        if (c == null
                || c.getStatus() != CaptureStatus.COMPLETED
                || !Objects.equals(s.getCaptureId(), c.getCaptureId())
                || !Objects.equals(s.getMerchantId(), c.getMerchantId())
                || !Objects.equals(s.getPaymentId(), c.getPaymentId())
                || !Objects.equals(s.getGrossAmount(), c.getCaptureAmount())) return "CAPTURE_MISMATCH";
        if (f == null
                || f.getMerchantId() == null
                || !Objects.equals(f.getMerchantId(), s.getMerchantId())
                || !Objects.equals(f.getOrderId(), c.getOrderId())
                || f.getFulfilledAt() == null) return "PICKUP_NOT_LINKED";
        if (s.getScheduledAt() == null
                || !s.getScheduledAt().equals(f.getFulfilledAt().plusSeconds(72 * 3600))
                || s.getScheduledAt().isAfter(now)) return "NOT_DUE";
        return "ELIGIBLE";
    }
}
