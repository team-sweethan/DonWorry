package com.donworry.fds.api;

import java.time.Instant;
import lombok.Builder;

// 내부자 경보 한 줄 (#679, ADR-0082 조각 10) — 관리자 내부자 관제 읽기 모양. 원문 · 고객 번호 · IP 칸 없음
// 열려 있으면 open = true이고 closedAt이 null이다
@Builder
public record InsiderAlertEntry(
        Long alertId,
        Long staffId,
        InsiderRule rule,
        InsiderAlertSeverity severity,
        boolean open,
        String summary,
        Instant createdAt,
        Instant closedAt) {}
