package com.donworry.fds.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

// 내부자 관제 읽기 창구 (#679, ADR-0082 조각 10) — P3 관제 화면이 직원 단계 색 · 타임라인 경보 줄에 쓴다. 읽기만
public interface InsiderAlertMonitorReader {

    // 모든 직원의 열린 경보 — 열린 경보는 사람 · 규칙당 1개라 많지 않다
    List<InsiderAlertEntry> findOpenAlerts();

    // 한 직원의 경보 중 [from, to]에 열렸거나 닫힌 것, 열린 시각 · 경보 번호 오름차순
    List<InsiderAlertEntry> findAlerts(Long staffId, Instant from, Instant to);

    // 모든 직원의 이 규칙들 경보 중 [from, to]에 열린 것, 최근 것부터 (#770 로그인 보안 대시보드 — INS-10 · INS-05)
    List<InsiderAlertEntry> findAlertsOpenedBetween(Collection<InsiderRule> rules, Instant from, Instant to);
}
