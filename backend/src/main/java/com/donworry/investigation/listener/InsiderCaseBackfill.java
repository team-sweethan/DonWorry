package com.donworry.investigation.listener;

import com.donworry.investigation.service.InsiderCaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 서버가 켜지면 열린 높음 이상 내부자 경보를 사건에 묶는다 (#670 Codex 리뷰)
// 사건 기능 배포 전에 생긴 경보에는 알림이 다시 오지 않아, 그때 정지된 직원의 사건이 없으면 판정 · 해제를 할 수 없다
// 이미 묶인 경보는 그대로라 켤 때마다 돌아도 같다. 실패해도 서버 시작은 막지 않는다 — 다음 시작 때 다시 묶는다
@Slf4j
@Component
@RequiredArgsConstructor
class InsiderCaseBackfill {

    private final InsiderCaseService insiderCaseService;

    @EventListener(ApplicationReadyEvent.class)
    void linkOpenAlerts() {
        try {
            int linked = insiderCaseService.linkOpenAlerts();
            if (linked > 0) log.info("열린 내부자 경보를 사건에 묶음 linked={}", linked);
        } catch (RuntimeException e) {
            log.warn("열린 내부자 경보를 사건에 묶지 못함 — 다음 시작 때 다시 시도", e);
        }
    }
}
