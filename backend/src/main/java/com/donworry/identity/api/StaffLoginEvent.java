package com.donworry.identity.api;

import java.time.Instant;
import lombok.Builder;

// 직원 로그인 · 실패 · 로그아웃 한 줄 (#679, ADR-0082 조각 10) — 관리자 내부자 관제 타임라인용. login_histories를 읽기만 한다
// clientIp는 직원 자신의 접속 IP다(관리자 [로그인 기록]과 같은 원문). 다른 표로 복사하지 않는다
// device는 "브라우저 · 운영체제" 이름(버전 없음). 브라우저 원문(User-Agent)은 내보내지 않는다
@Builder
public record StaffLoginEvent(
        Long staffId, StaffLoginEventType eventType, Instant occurredAt, String clientIp, String device) {}
