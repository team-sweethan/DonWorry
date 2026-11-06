package com.donworry.fds.repository;

import java.time.Instant;

public interface OperatorAccessLowRiskSignalProjection {
    Long getSourceEventId();

    String getLoginId();

    Long getMerchantId();

    String getDeniedType();

    Integer getEventCount();

    Instant getOccurredAt();
}
