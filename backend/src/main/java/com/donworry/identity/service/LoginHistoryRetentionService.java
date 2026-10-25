package com.donworry.identity.service;

import com.donworry.identity.repository.LoginHistoryRepository;
import java.time.Clock;
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
    private final LoginHistoryRepository histories;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    public LoginHistoryRetentionService(
            LoginHistoryRepository histories,
            PlatformTransactionManager transactions,
            @Qualifier("loginAttemptClock") Clock clock) {
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
        try {
            for (int batch = 0; batch < 10; batch++) {
                // native query의 LocalDateTime 바인딩에 JVM 시간대가 재적용되지 않도록 UTC DATETIME을 명시한다.
                Integer count = tx.execute(status -> histories.deleteExpiredBatch(
                        cutoff.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))));
                if (count == null || count < 1000) return;
            }
            log.warn("로그인 이력 삭제 최대 배치 도달");
        } catch (RuntimeException exception) {
            log.error("로그인 이력 삭제 실패 errorType={}", exception.getClass().getSimpleName());
        }
    }
}
