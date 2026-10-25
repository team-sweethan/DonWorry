package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.repository.PlatformUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class LoginAttemptServiceTests {
    private final PlatformUserRepository repository = mock(PlatformUserRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final Instant instant = Instant.parse("2026-10-25T03:00:00.123456789Z");
    private final LocalDateTime now = LocalDateTime.parse("2026-10-25T03:00:00.123456");
    private final LoginAttemptService service =
            new LoginAttemptService(repository, transactions, Clock.fixed(instant, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(transaction);
    }

    // UTC 마이크로초 기준으로 만료 직전은 차단하고 정확히 만료된 시각부터 허용한다
    @Test
    void isLocked_whenAtExpiryBoundary_allowsOnlyExpiredLocks() {
        assertThat(service.isLocked(null)).isFalse();
        assertThat(service.isLocked(now.plusNanos(1000))).isTrue();
        assertThat(service.isLocked(now)).isFalse();
        assertThat(service.isLocked(now.minusNanos(1000))).isFalse();
    }

    // 증가와 종료 시각을 UPDATE 한 번으로 넘기고 외부 실패와 독립적으로 커밋한다
    @Test
    void recordFailure_whenValid_commitsIndependentTransactionWithUtcPrecision() {
        service.recordFailure("user");
        var order = inOrder(repository, transactions);
        order.verify(repository).recordLoginFailure("user", now, now.plusMinutes(15));
        order.verify(transactions).commit(transaction);
        verify(transactions)
                .getTransaction(argThat(definition ->
                        definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    // UPDATE 오류는 롤백하고 인증 장애로 전파한다
    @Test
    void recordFailure_whenUpdateFails_rollsBackAndReportsAuthenticationFailure() {
        var cause = new DataAccessResourceFailureException("db down");
        doThrow(cause).when(repository).recordLoginFailure(anyString(), any(), any());
        assertThatThrownBy(() -> service.recordFailure("user"))
                .isInstanceOf(InternalAuthenticationServiceException.class)
                .hasCause(cause);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    // 커밋 실패를 정상적인 비밀번호 오류로 숨기지 않는다
    @Test
    void recordFailure_whenCommitFails_reportsAuthenticationFailure() {
        var cause = new TransactionSystemException("commit failed");
        doThrow(cause).when(transactions).commit(transaction);
        assertThatThrownBy(() -> service.recordFailure("user"))
                .isInstanceOf(InternalAuthenticationServiceException.class)
                .hasCause(cause);
    }

    // 성공 초기화도 세션 등록 전에 완료할 수 있도록 독립 커밋한다
    @Test
    void clearFailures_whenValid_commitsReset() {
        service.clearFailures(1L);
        var order = inOrder(repository, transactions);
        order.verify(repository).clearLoginFailures(1L);
        order.verify(transactions).commit(transaction);
    }
}
