package com.donworry.identity.service;

import com.donworry.identity.repository.OperatorAccessDenialRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class OperatorAccessDenialRetentionService {
    private static final Duration RUN_BUDGET = Duration.ofMinutes(5);

    private final OperatorAccessDenialRepository denials;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    public OperatorAccessDenialRetentionService(
            OperatorAccessDenialRepository denials,
            PlatformTransactionManager transactions,
            @Qualifier("identityClock") Clock clock) {
        this.denials = denials;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
    public void clearExpiredIps() {
        var cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS)
                .minus(AccessRecordRetentionPolicy.RETENTION_PERIOD);
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 대량의 만료 원본도 독립 배치로 처리하고 한 실행의 반복 시간을 제한한다.
        long deadline = System.nanoTime() + RUN_BUDGET.toNanos();
        try {
            while (true) {
                // native query의 LocalDateTime 바인딩에 JVM 시간대가 재적용되지 않도록 UTC DATETIME을 명시한다.
                Integer count = tx.execute(status -> denials.clearExpiredIpsBatch(
                        cutoff.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))));
                if (count == null || count < 1000) return;
                if (System.nanoTime() - deadline >= 0) {
                    log.warn("403 원본 IP 정리 실행 시간 상한 도달");
                    return;
                }
            }
        } catch (RuntimeException exception) {
            log.error("403 원본 IP 정리 실패 errorType={}", exception.getClass().getSimpleName());
        }
    }
}
