package com.donworry.identity.service;

import com.donworry.identity.entity.*;
import com.donworry.identity.repository.LoginHistoryRepository;
import com.donworry.identity.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class LoginHistoryService {
    private final LoginHistoryRepository histories;
    private final LoginHistoryRequestMetadata metadata;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    public LoginHistoryService(
            LoginHistoryRepository histories,
            LoginHistoryRequestMetadata metadata,
            PlatformTransactionManager transactions,
            @Qualifier("loginAttemptClock") Clock clock) {
        this.histories = histories;
        this.metadata = metadata;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void success(SessionUser user, HttpServletRequest request) {
        record(user.userId(), LoginEventType.LOGIN_SUCCESS, user.loginReference(), null, request);
    }

    public void failure(Long userId, HttpServletRequest request) {
        record(userId, LoginEventType.LOGIN_FAILURE, null, null, request);
    }

    public void logout(SessionUser user, boolean all, HttpServletRequest request) {
        if (user == null || user.loginReference() == null) return;
        record(
                user.userId(),
                LoginEventType.LOGOUT,
                user.loginReference(),
                all ? LogoutScope.ALL : LogoutScope.CURRENT,
                request);
    }

    private void record(
            Long userId, LoginEventType type, UUID reference, LogoutScope scope, HttpServletRequest request) {
        try {
            var history = LoginHistory.record(
                    userId,
                    type,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
                    metadata.clientIp(request),
                    metadata.userAgent(request),
                    reference,
                    scope);
            independent().executeWithoutResult(status -> histories.saveAndFlush(history));
        } catch (RuntimeException exception) {
            if (type == LoginEventType.LOGOUT
                    && exception instanceof DataIntegrityViolationException integrity
                    && integrity.getMostSpecificCause().getMessage() != null
                    && integrity.getMostSpecificCause().getMessage().contains("uk_login_histories_reference_event")) {
                try {
                    if (Boolean.TRUE.equals(independent()
                            .execute(status -> histories.existsByLoginReferenceAndEventType(reference, type)))) return;
                } catch (RuntimeException ignored) {
                    // 중복 여부까지 확인할 수 없으면 저장 실패로 남기되 로그아웃 결과를 유지한다.
                }
            }
            // DB 예외 메시지에는 INSERT의 IP·User-Agent가 들어갈 수 있어 원문/stack trace를 기록하지 않는다.
            log.error(
                    "로그인 이력 저장 실패 eventType={} userId={} errorType={}",
                    type,
                    userId,
                    exception.getClass().getSimpleName());
        }
    }

    private TransactionTemplate independent() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }
}
