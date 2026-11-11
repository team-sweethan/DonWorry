package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// 내부자 관제 타임라인 한 줄 (#679, ADR-0082 조각 10) — kind별로 쓰는 칸만 채운다. 원문 · 고객 번호 칸은 없다
// kind = LOGIN(type = LOGIN_SUCCESS · LOGIN_FAILURE · LOGOUT) · ACCESS(type = 접근 종류) · ALERT(ALERT_RAISED ·
// ALERT_CLOSED) · LOCK(LOCKED · RELEASED)
// clientIp는 LOGIN 줄의 직원 자신의 접속 IP다 (관리자 [로그인 기록]과 같은 원문, ADR-0082 조각 10 D1)
// customerScope는 고객 번호로 검색한 조회만 RELATED(담당 사건 고객) · UNRELATED(무관 고객)
@Builder
public record InsiderMonitorEventResponse(
        Instant occurredAt,
        String kind,
        String type,
        String clientIp,
        String device,
        String result,
        String denyReason,
        Long caseId,
        Long merchantId,
        String merchantName,
        Long paymentId,
        String fieldType,
        Integer itemCount,
        String customerScope,
        Long alertId,
        String ruleCode,
        String ruleLabel,
        String severity,
        String summary,
        String lockReason,
        Long insiderCaseId,
        String decision) {}
