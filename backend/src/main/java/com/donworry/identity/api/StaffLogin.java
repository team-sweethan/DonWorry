package com.donworry.identity.api;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

// 직원 로그인 성공 기록 한 줄 (#643, ADR-0082) — login_histories를 읽기만 한다
// device는 "브라우저 · 운영체제" 이름(예: "Edge · Windows", 버전 없음). IP · 브라우저 원문은 내보내지 않는다
@Builder
public record StaffLogin(Long staffId, UUID loginReference, Instant occurredAt, String device) {}
