package com.donworry.fds.api;

// 열린 내부자 경보 조회 창구 (#516, ADR-0082) — 경보 중 내보내기 차단(7b) · 내부자 사건(8)이 쓴다
public interface InsiderAlertReader {
    boolean hasOpenAlert(Long staffId);
}
