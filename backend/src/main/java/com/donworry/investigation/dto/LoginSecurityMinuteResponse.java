package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// 1분 막대 하나 (#770) — 시도가 없던 분도 0으로 채운다
@Builder
public record LoginSecurityMinuteResponse(
        Instant minuteStart, long attempts, long failures, long unknownAccountFailures) {}
