package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// 로그인 공격 관련 내부자 경보 한 줄 (#770) — SEC-INS-10(탈취 의심) · SEC-INS-05(내보내기 차단 · 정지)
// insiderCaseId = 그 직원의 열린 내부자 사건(없으면 null) — 화면이 사건 상세로 넘어갈 때 쓴다
@Builder
public record LoginSecurityAlertResponse(
        Long alertId,
        Long staffId,
        String staffName,
        String ruleCode,
        String ruleLabel,
        String severity,
        String summary,
        boolean open,
        Instant createdAt,
        Long insiderCaseId) {}
