package com.donworry.settlement.repository;

import com.donworry.settlement.entity.CaptureStatus;
import com.donworry.settlement.entity.SettlementStatus;
import java.time.Instant;

public interface CaptureStatusProjection {
    Long getPaymentId();

    Long getCaptureId();

    CaptureStatus getCaptureStatus();

    Long getCaptureAmount();

    Instant getCapturedAt();

    Long getSettlementId();

    SettlementStatus getSettlementStatus();
}
