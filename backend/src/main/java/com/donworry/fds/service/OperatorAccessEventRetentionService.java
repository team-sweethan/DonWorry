package com.donworry.fds.service;

import com.donworry.fds.repository.OperatorAccessEventRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** P1 원본과 동일하게 IP만 1년 뒤 비식별화하고 조사 근거 행은 보존한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class OperatorAccessEventRetentionService {
    private static final int BATCH_SIZE = 1_000;
    private final OperatorAccessEventRepository events;
    private final Clock clock;

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    @Transactional
    public void clearExpiredClientIps() {
        var cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusYears(1)
                .truncatedTo(ChronoUnit.MICROS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
        try {
            while (events.clearExpiredClientIpBatch(cutoff) == BATCH_SIZE) {
                // 짧은 배치로 잠금 범위를 제한한다.
            }
        } catch (RuntimeException exception) {
            log.error("사장님 접근 위반 IP 비식별화 실패 errorType={}", exception.getClass().getSimpleName());
        }
    }
}
