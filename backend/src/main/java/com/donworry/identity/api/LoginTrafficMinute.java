package com.donworry.identity.api;

import java.time.Instant;
import lombok.Builder;

// 1분 단위 전체 로그인 시도 · 실패 수 (#770) — minuteStart는 UTC 분 시작 시각. 시도가 없던 분은 내보내지 않는다
@Builder
public record LoginTrafficMinute(Instant minuteStart, long attempts, long failures, long unknownAccountFailures) {}
