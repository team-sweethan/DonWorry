package com.donworry.identity.api;

import java.time.Instant;
import lombok.Builder;

// 일정 길이 칸 하나의 전체 로그인 시도 · 실패 수 (#770) — start는 칸 시작 시각(UTC). unknownAccountFailures는 실패에 포함
@Builder
public record LoginTrafficBucket(Instant start, long attempts, long failures, long unknownAccountFailures) {}
