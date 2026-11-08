package com.donworry.fds.api;

import java.util.List;

// 열린 내부자 경보 조회 창구 (#516, ADR-0082) — 경보 중 내보내기 차단(7b) · 내부자 사건(8)이 쓴다
public interface InsiderAlertReader {
    boolean hasOpenAlert(Long staffId);

    // 내부자 사건(#670)이 늦게 도착한 경보 알림을 거를 때 — 판정이 이미 닫은 경보로 사건을 다시 열지 않는다. 없는 경보면 false
    boolean isOpen(Long alertId);

    // 열린 높음 이상 경보를 알림(InsiderAlertRaised)과 같은 모양으로 — 오래된 것부터. 내부자 사건(#670)이 서버 시작 때
    // 사건 기능 배포 전부터 열려 있던 경보를 사건에 묶는 데 쓴다(알림은 새 경보 · 등급 상승 때만 오므로). raisedAt은 경보 갱신 시각
    List<InsiderAlertRaised> findOpenRaisedAlerts();
}
