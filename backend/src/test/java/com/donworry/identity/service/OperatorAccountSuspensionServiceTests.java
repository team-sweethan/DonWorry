package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OperatorAccountSuspensionServiceTests {
    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final AdminChangeHistoryService histories = mock(AdminChangeHistoryService.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PlatformUser target = mock(PlatformUser.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final OperatorAccountSuspensionService service =
            new OperatorAccountSuspensionService(users, histories, new AccountLockService(), sessions, transactions);

    @BeforeEach
    void setUp() {
        when(users.findById(1L)).thenReturn(Optional.of(target));
        when(target.getLoginId()).thenReturn("owner");
        when(target.getRole()).thenReturn(UserRole.OPERATOR);
        when(target.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(transactions.getTransaction(any())).thenReturn(transaction);
    }

    // 감사와 상태를 새 트랜잭션으로 커밋한 뒤에만 세션을 만료한다
    @Test
    void suspend_whenActive_commitsBeforeExpiringSessions() {
        service.suspend(1L);
        var order = inOrder(target, histories, transactions, sessions);
        order.verify(target).changeStatus(UserStatus.INACTIVE);
        order.verify(histories).recordSystemUser(eq(1L), eq("USER_SUSPENDED_BY_FDS"), any(), any());
        order.verify(transactions).commit(transaction);
        order.verify(sessions).expireAll(1L);
        verify(users, times(2)).findById(1L);
        verify(transactions)
                .getTransaction(argThat(definition ->
                        definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    // 커밋에 실패하면 세션을 만료하지 않는다
    @Test
    void suspend_whenCommitFails_doesNotExpireSessions() {
        doThrow(new TransactionSystemException("commit failed"))
                .when(transactions)
                .commit(transaction);
        assertThatThrownBy(() -> service.suspend(1L)).isInstanceOf(TransactionSystemException.class);
        verifyNoInteractions(sessions);
    }

    // 감사 저장에 실패하면 트랜잭션을 롤백하고 세션을 유지한다
    @Test
    void suspend_whenAuditFails_rollsBackWithoutExpiringSessions() {
        doThrow(new IllegalStateException("audit failed"))
                .when(histories)
                .recordSystemUser(any(), anyString(), any(), any());
        assertThatThrownBy(() -> service.suspend(1L)).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        verifyNoInteractions(sessions);
    }

    // 이미 정지된 사장님은 감사와 세션에 손대지 않는다
    @Test
    void suspend_whenInactive_doesNotWriteHistoryOrExpireSessions() {
        when(target.getStatus()).thenReturn(UserStatus.INACTIVE);
        service.suspend(1L);
        verify(target, never()).changeStatus(any());
        verifyNoInteractions(histories, sessions);
    }
}
