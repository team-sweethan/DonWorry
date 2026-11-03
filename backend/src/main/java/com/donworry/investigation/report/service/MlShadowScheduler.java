package com.donworry.investigation.report.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 커밋된 FDS 평가를 주기적으로 ML 채점해 기록한다 (설계 375 9절) — 켠 환경에서만 돈다. 결제 판정에는 쓰지 않는다
// 테스트는 예약 작업을 끄고 MlShadowService.runOnce를 직접 부른다
@Component
@ConditionalOnProperty(name = "ai.ml-shadow.enabled", havingValue = "true")
@RequiredArgsConstructor
class MlShadowScheduler {

    private final MlShadowService mlShadowService;

    @Scheduled(fixedDelayString = "${ai.ml-shadow.interval:PT30S}")
    void run() {
        mlShadowService.runOnce(Instant.now());
    }
}
