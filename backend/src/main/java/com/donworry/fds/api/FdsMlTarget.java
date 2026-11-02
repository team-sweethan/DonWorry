package com.donworry.fds.api;

import java.time.Instant;
import lombok.Builder;

// ML과 나란히 기록할 Rule 판정 스냅샷 (#409)
@Builder
public record FdsMlTarget(
        Long evaluationId, Long paymentId, FdsResult result, Integer riskScore, Instant evaluatedAt) {}
