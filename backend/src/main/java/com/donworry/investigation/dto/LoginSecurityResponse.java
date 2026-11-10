package com.donworry.investigation.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// 관리자 로그인 보안 대시보드 (#770, ADR-0082) — 전체 로그인 실패율(IP 구분 없음) · 1분 막대 · 보안 직원 로그인 · 탈취 의심 경보
// recent*는 탐지와 같은 최근 windowMinutes 구간. 기준값은 탐지(security.login-attack.*)와 같다
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
        List<LoginSecurityMinuteResponse> minutes,
        List<LoginSecurityStaffLoginResponse> staffLogins,
        List<LoginSecurityAlertResponse> alerts) {}
