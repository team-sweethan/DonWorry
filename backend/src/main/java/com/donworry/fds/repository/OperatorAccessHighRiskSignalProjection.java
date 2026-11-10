package com.donworry.fds.repository;

import java.time.Instant;

public interface OperatorAccessHighRiskSignalProjection {
    Long getDetectionId();

    String getLoginId();

    Long getMerchantId();

    String getRuleCode();

    Integer getEventCount();

    String getActionStatus();

    Instant getReviewedAt();

    Instant getOccurredAt();
}
