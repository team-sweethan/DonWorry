package com.donworry.settlement.api;

import java.time.Instant;
import lombok.Builder;

@Builder
public record CaptureCompleted(Long paymentId, Long captureId, Instant capturedAt) {}
