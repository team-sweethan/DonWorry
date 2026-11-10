package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// 실시간 시도 한 줄 (#770) — account = UNKNOWN(없는 아이디) · SECURITY_STAFF · ADMIN · OPERATOR
// staffName은 보안 직원일 때만. 다른 계정은 이름 없이 종류만 보인다. IP · 시도한 아이디는 없다
// suspected = 이 성공 로그인 직후 같은 직원에게 SEC-INS-10(탈취 의심)이 열림
@Builder
public record LoginSecurityAttemptResponse(
        Instant occurredAt,
        boolean success,
        String account,
        Long staffId,
        String staffName,
        String device,
        boolean suspected) {}
