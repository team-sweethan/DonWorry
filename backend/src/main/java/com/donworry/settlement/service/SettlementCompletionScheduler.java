package com.donworry.settlement.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "settlement.completion.enabled", havingValue = "true")
public class SettlementCompletionScheduler {
    private final SettlementCompletionBatchService batch;

    // 한국 시간 매일 03시가 기본이다. 검증 후 명시적으로 활성화한다.
    @Scheduled(cron = "${settlement.completion.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void run() {
        var result = batch.run(Instant.now());
        log.info(
                "settlement completion batch: cutoff={}, scanned={}, completed={}, skipped={}, failed={}",
                result.cutoff(),
                result.scanned(),
                result.completed(),
                result.skipped(),
                result.failed());
    }
}
