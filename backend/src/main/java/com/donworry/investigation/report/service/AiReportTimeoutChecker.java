package com.donworry.investigation.report.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결과 콜백이 오지 않은 조사를 주기적으로 확인한다 (ADR-0009 결정 1 "콜백 유실 복구")
// ai-server를 켰을 때만 돈다. 테스트는 예약 작업을 끄고 AiReportResultService.checkOverdue를 직접 부른다
@Component
@ConditionalOnProperty(name = "ai.server.enabled", havingValue = "true")
@RequiredArgsConstructor
class AiReportTimeoutChecker {

    private final AiReportResultService resultService;

    @Scheduled(fixedDelayString = "${ai.server.check-interval:PT30S}")
    void checkOverdue() {
        resultService.checkOverdue(Instant.now());
    }
}
