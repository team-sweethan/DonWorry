package com.donworry.investigation.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// 관리자 로그인 보안 대시보드 (#770, ADR-0082) — 전체 로그인 실패율(IP 구분 없음) · 10초 꺾은선 · 실시간 시도 · 탈취 의심 경보
// recent*는 탐지와 같은 최근 windowMinutes 구간. 기준값은 탐지(security.login-attack.*)와 같다
// buckets는 같은 구간을 bucketSeconds씩 나눈 칸(빈 칸 0), attempts는 최근 시도 최신순
@Builder
public record LoginSecurityResponse(
        Instant generatedAt,
        LoginSecurityState state,
        int windowMinutes,
        long minAttempts,
        int failureRatePercentThreshold,
        long recentAttempts,
        long recentFailures,
        long recentUnknownAccountFailures,
        int recentFailureRatePercent,
        long suspectedTakeoverCount,
        int bucketSeconds,
        List<LoginSecurityBucketResponse> buckets,
        List<LoginSecurityAttemptResponse> attempts,
        List<LoginSecurityAlertResponse> alerts) {}
