package com.donworry.settlement.api;

import java.time.Instant;
import lombok.Builder;

@Builder
public record CaptureSnapshot(
        Long paymentId,
        Long captureId,
        String captureStatus,
        Long captureAmount,
        Instant capturedAt,
        boolean settlementExists,
        String settlementStatus) {}
