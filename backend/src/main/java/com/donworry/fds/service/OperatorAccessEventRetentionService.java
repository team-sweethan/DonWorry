package com.donworry.fds.service;

import com.donworry.fds.repository.OperatorAccessEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** P1 원본과 동일하게 IP만 1년 뒤 비식별화하고 조사 근거 행은 보존한다. */
@Service
@Slf4j
public class OperatorAccessEventRetentionService {
    private static final int BATCH_SIZE = 1_000;
    private static final Duration RUN_BUDGET = Duration.ofMinutes(5);
    private final OperatorAccessEventRepository events;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    public OperatorAccessEventRetentionService(
            OperatorAccessEventRepository events, PlatformTransactionManager transactions, Clock clock) {
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void clearExpiredClientIps() {
        var cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusYears(1)
                .truncatedTo(ChronoUnit.MICROS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long deadline = System.nanoTime() + RUN_BUDGET.toNanos();
        try {
            while (true) {
                Integer count = tx.execute(status -> events.clearExpiredClientIpBatch(cutoff));
                if (count == null || count < BATCH_SIZE) return;
                if (System.nanoTime() - deadline >= 0) {
                    log.warn("사장님 접근 위반 IP 비식별화 실행 시간 상한 도달");
                    return;
                }
            }
        } catch (RuntimeException exception) {
            log.error("사장님 접근 위반 IP 비식별화 실패 errorType={}", exception.getClass().getSimpleName());
        }
    }
}
