package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// 보안 직원 로그인 한 줄 (#770) — eventType = LOGIN_SUCCESS · LOGIN_FAILURE. device는 "브라우저 · 운영체제"(버전 없음). IP는 넣지 않는다
@Builder
public record LoginSecurityStaffLoginResponse(
        Long staffId, String staffName, String eventType, Instant occurredAt, String device) {}
