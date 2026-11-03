package com.donworry.settlement.service;

import com.donworry.settlement.entity.*;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 취소 연동 전 범위의 완료 조건이다. 취소 동시 실행 보장은 후속 계약에서 확장한다. */
@Component
@RequiredArgsConstructor
public class SettlementCompletionPolicy {
    private final CaptureSettlementSchedule schedule;

    public String evaluate(Settlement s, Capture c, Instant now) {
        if (s.getStatus() != SettlementStatus.SCHEDULED) return "NOT_SCHEDULED";
        if (c == null
                || c.getStatus() != CaptureStatus.COMPLETED
                || !Objects.equals(s.getCaptureId(), c.getCaptureId())
                || !Objects.equals(s.getMerchantId(), c.getMerchantId())
                || !Objects.equals(s.getPaymentId(), c.getPaymentId())
                || !Objects.equals(s.getGrossAmount(), c.getCaptureAmount())) return "CAPTURE_MISMATCH";
        if (c.getCapturedAt() == null) return "CAPTURE_MISMATCH";
        if (s.getScheduledAt() == null
                || !s.getScheduledAt().equals(schedule.settlementAt(c.getCapturedAt()))
                || now == null
                || s.getScheduledAt().isAfter(now)) return "NOT_DUE";
        return "ELIGIBLE";
    }
}
