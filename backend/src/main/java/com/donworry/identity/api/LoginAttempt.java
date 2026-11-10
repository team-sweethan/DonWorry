package com.donworry.identity.api;

import java.time.Instant;
import lombok.Builder;

// 로그인 시도 한 줄 (#770 로그인 보안 대시보드) — userId · role이 null이면 없는 아이디로 실패한 시도
// role = SECURITY_STAFF · ADMIN · OPERATOR. device는 "브라우저 · 운영체제"(버전 없음). IP · 시도한 아이디는 내보내지 않는다
@Builder
public record LoginAttempt(Instant occurredAt, boolean success, Long userId, String role, String device) {}
