package com.donworry.identity.service;

import com.donworry.identity.repository.LoginHistoryRepository;
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
public class LoginHistoryRetentionService {
    private static final Duration RUN_BUDGET = Duration.ofMinutes(5);

    private final LoginHistoryRepository histories;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    public LoginHistoryRetentionService(
            LoginHistoryRepository histories,
            PlatformTransactionManager transactions,
            @Qualifier("identityClock") Clock clock) {
        this.histories = histories;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
    public void deleteExpired() {
        var cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS)
                .minusDays(90);
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 없는 아이디의 실패는 잠금 없이 계속 쌓일 수 있어 배치 수가 아닌 실행 시간으로 상한을 둔다.
        long deadline = System.nanoTime() + RUN_BUDGET.toNanos();
        try {
            while (true) {
                // native query의 LocalDateTime 바인딩에 JVM 시간대가 재적용되지 않도록 UTC DATETIME을 명시한다.
                Integer count = tx.execute(status -> histories.deleteExpiredBatch(
                        cutoff.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))));
                if (count == null || count < 1000) return;
                if (System.nanoTime() - deadline >= 0) {
                    log.warn("로그인 이력 삭제 실행 시간 상한 도달");
                    return;
                }
            }
        } catch (RuntimeException exception) {
            log.error("로그인 이력 삭제 실패 errorType={}", exception.getClass().getSimpleName());
        }
    }
}
