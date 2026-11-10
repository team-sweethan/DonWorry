package com.donworry.fds.api;

import java.time.Instant;
import lombok.Builder;

// 내부자 경보 한 줄 (#679, ADR-0082 조각 10) — 관리자 내부자 관제 읽기 모양. 원문 · 고객 번호 · IP 칸 없음
// 열려 있으면 open = true이고 closedAt이 null이다
// firstEventAt · lastEventAt = 경보가 가리키는 첫 · 마지막 기록 시각. SEC-INS-10은 탐지한 로그인 시각(#777 — 대시보드가 그 로그인에만 탈취 의심을 붙인다)
@Builder
public record InsiderAlertEntry(
        Long alertId,
        Long staffId,
        InsiderRule rule,
        InsiderAlertSeverity severity,
        boolean open,
        String summary,
        Instant createdAt,
        Instant closedAt,
        Instant firstEventAt,
        Instant lastEventAt) {}
