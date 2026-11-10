package com.donworry.investigation.dto;

import java.time.Instant;

// 경계 구간 하나 (#788) — 1초 칸으로 센 경계 시작 · 끝. 그래프 범위 앞에서 이미 경계였으면 start null(진입 시각을 지어내지 않는다),
// 아직 경계 중이면 end null
public record LoginSecurityPeriodResponse(Instant start, Instant end) {}
