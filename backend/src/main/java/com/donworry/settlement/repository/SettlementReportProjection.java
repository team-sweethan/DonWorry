package com.donworry.settlement.repository;

import com.donworry.settlement.entity.SettlementStatus;
import java.time.Instant;

public interface SettlementReportProjection {
    Long getSettlementId();

    Long getCaptureId();

    Long getPaymentId();

    String getOrderId();

    Long getGrossAmount();

    Long getCardFeeAmount();

    Long getPgFeeAmount();

    Long getSettlementAmount();

    SettlementStatus getStatus();

    Instant getExpectedSettlementAt();

    Instant getScheduledAt();

    Instant getCompletedAt();

    Instant getScheduledPickupAt();

    Instant getPickedUpAt();
}
