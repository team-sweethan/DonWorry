package com.donworry.settlement.service;

import com.donworry.settlement.entity.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 취소 연동 전 범위의 완료 조건이다. 취소 동시 실행 보장은 후속 계약에서 확장한다. */
@Component
public class SettlementCompletionPolicy {
    public String evaluate(Settlement s, Capture c, Instant now) {
        if (s.getStatus() != SettlementStatus.SCHEDULED) return "NOT_SCHEDULED";
        if (c == null
                || c.getStatus() != CaptureStatus.COMPLETED
                || !Objects.equals(s.getCaptureId(), c.getCaptureId())
                || !Objects.equals(s.getMerchantId(), c.getMerchantId())
                || !Objects.equals(s.getPaymentId(), c.getPaymentId())
                || !Objects.equals(s.getGrossAmount(), c.getCaptureAmount())) return "CAPTURE_MISMATCH";
        if (c.getCapturedAt() == null) return "CAPTURE_MISMATCH";
        // 생성 시 확정한 정산일을 사용한다. 이후 달력 변경으로 지급을 막지 않는다.
        if (s.getScheduledAt() == null || now == null || s.getScheduledAt().isAfter(now)) return "NOT_DUE";
        return "ELIGIBLE";
    }
}
