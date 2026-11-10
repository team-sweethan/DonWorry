package com.donworry.identity.service;

import com.donworry.identity.api.StaffLoginUnderAttack;
import com.donworry.identity.entity.LoginEventType;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.LoginHistoryRepository;
import com.donworry.identity.security.SessionUser;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// 크리덴셜 스터핑 경계 (#770, ADR-0082) — 보안 직원 로그인이 성공할 때만 최근 구간 전체 로그인 실패율을 센다
// IP는 보지 않는다(공격자가 IP를 바꿔 가며 시도). 경계 상태를 따로 저장하지 않고 성공할 때마다 다시 센다
// 공격 중이면 알림만 보낸다. 탈취 의심 판단(처음 보는 브라우저)과 차단은 P2 SEC-INS-10 → 기존 INS-05가 한다
@Service
@Slf4j
public class LoginAttackCheck {
    private static final List<LoginEventType> ATTEMPTS =
            List.of(LoginEventType.LOGIN_SUCCESS, LoginEventType.LOGIN_FAILURE);

    private final LoginHistoryRepository histories;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate separateTransaction;
    private final Clock clock;
    private final Duration window;
    private final long minAttempts;
    private final double failureRatio;

    public LoginAttackCheck(
            LoginHistoryRepository histories,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactions,
            @Qualifier("identityClock") Clock clock,
            @Value("${security.login-attack.window:5m}") Duration window,
            @Value("${security.login-attack.min-attempts:30}") long minAttempts,
            @Value("${security.login-attack.failure-ratio:0.3}") double failureRatio) {
        this.histories = histories;
        this.events = events;
        // 알림은 커밋 뒤 전달되므로 트랜잭션 안에서 보낸다. 로그인 자체는 트랜잭션이 없다
        this.separateTransaction = new TransactionTemplate(transactions);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.window = window;
        this.minAttempts = minAttempts;
        this.failureRatio = failureRatio;
    }

    // 로그인 이력을 남긴 뒤 부른다. 집계 · 알림이 실패해도 로그인은 성공시킨다(예외를 밖으로 내지 않음)
    public void afterLogin(SessionUser user) {
        if (user.role() != UserRole.SECURITY_STAFF) return;
        try {
            separateTransaction.executeWithoutResult(status -> check(user));
        } catch (RuntimeException exception) {
            log.error(
                    "로그인 공격 경계 확인 실패 userId={} errorType={}",
                    user.userId(),
                    exception.getClass().getSimpleName());
        }
    }

    private void check(SessionUser user) {
        var now = clock.instant();
        var to = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        var from = to.minus(window);
        long attempts =
                histories.countByEventTypeInAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(ATTEMPTS, from, to);
        if (attempts < minAttempts) return;
        long failures = histories.countByEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
                LoginEventType.LOGIN_FAILURE, from, to);
        if ((double) failures / attempts < failureRatio) return;
        log.warn("로그인 공격 경계 중 보안 직원 로그인 userId={} attempts={} failures={}", user.userId(), attempts, failures);
        events.publishEvent(StaffLoginUnderAttack.builder()
                .staffId(user.userId())
                .loginReference(user.loginReference())
                .loggedInAt(now)
                .attemptCount(attempts)
                .failureCount(failures)
                .build());
    }
}
