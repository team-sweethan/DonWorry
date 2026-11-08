package com.donworry.fds.api;

// 열린 내부자 경보 조회 창구 (#516, ADR-0082) — 경보 중 내보내기 차단(7b) · 내부자 사건(8)이 쓴다
public interface InsiderAlertReader {
    boolean hasOpenAlert(Long staffId);

    // 내부자 사건(#670)이 늦게 도착한 경보 알림을 거를 때 — 판정이 이미 닫은 경보로 사건을 다시 열지 않는다. 없는 경보면 false
    boolean isOpen(Long alertId);
}
