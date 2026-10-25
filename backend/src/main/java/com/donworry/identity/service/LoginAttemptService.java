package com.donworry.identity.service;

import com.donworry.identity.repository.PlatformUserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {
    private final PlatformUserRepository repository;
    private final PlatformTransactionManager transactionManager;
    private final Clock loginAttemptClock;

    public boolean isLocked(LocalDateTime lockedUntil) {
        return lockedUntil != null && now().isBefore(lockedUntil);
    }

    public void recordFailure(String loginId) {
        var now = now();
        commit(() -> repository.recordLoginFailure(loginId, now, now.plusMinutes(15)));
    }

    public void clearFailures(Long userId) {
        commit(() -> repository.clearLoginFailures(userId));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(loginAttemptClock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private void commit(Runnable update) {
        // 바깥 로그인 실패 예외나 외부 트랜잭션의 롤백과 관계없이 실패를 먼저 확정한다.
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            transaction.executeWithoutResult(status -> update.run());
        } catch (RuntimeException exception) {
            throw new InternalAuthenticationServiceException("로그인 시도 정보를 저장하지 못했습니다.", exception);
        }
    }
}
