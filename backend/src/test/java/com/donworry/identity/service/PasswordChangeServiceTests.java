package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.dto.ChangePasswordRequest;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.PasswordChangeException;
import com.donworry.identity.security.SessionUser;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PasswordChangeServiceTests {
    private final PlatformUserRepository repository = mock(PlatformUserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final PlatformUser entity = mock(PlatformUser.class);
    private final SessionUser current =
            SessionUser.builder().userId(1L).loginId("user").build();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final PasswordChangeService service = new PasswordChangeService(
            repository, encoder, new PasswordPolicy(), new AccountLockService(), sessions, transactions);

    @BeforeEach
    void setUp() {
        request.getSession();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(entity.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(entity.getLoginId()).thenReturn("user");
        when(entity.getPasswordHash()).thenReturn("old-hash");
        when(encoder.matches("1234", "old-hash")).thenReturn(true);
        when(encoder.encode("newPass123")).thenReturn("new-hash");
    }

    // 기존 규칙의 현재 비밀번호도 확인하고 커밋 후 현재 외 세션을 종료한다
    @Test
    void change_whenValid_expiresOtherSessionsOnlyAfterCommit() {
        service.change(
                current,
                new ChangePasswordRequest("1234", "newPass123"),
                request,
                new org.springframework.mock.web.MockHttpServletResponse());
        var order = inOrder(entity, transactions, sessions);
        order.verify(sessions).requireActive(1L, request.getSession().getId());
        order.verify(entity).changePasswordHash("new-hash");
        order.verify(transactions).commit(transaction);
        order.verify(sessions).expireAllExcept(1L, request.getSession().getId());
        verify(transactions)
                .getTransaction(argThat(definition ->
                        definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    // 커밋 실패 시 현재 세션과 다른 세션을 만료시키지 않는다
    @Test
    void change_whenCommitFails_doesNotExpireSessions() {
        doThrow(new TransactionSystemException("commit failed"))
                .when(transactions)
                .commit(transaction);
        assertThatThrownBy(() -> service.change(
                        current,
                        new ChangePasswordRequest("1234", "newPass123"),
                        request,
                        new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOf(TransactionSystemException.class);
        verify(sessions, never()).expireAllExcept(any(), any());
    }

    // 현재 비밀번호 오류는 롤백하고 해시와 세션을 바꾸지 않는다
    @Test
    void change_whenCurrentPasswordWrong_rollsBackWithoutChanges() {
        assertThatThrownBy(() -> service.change(
                        current,
                        new ChangePasswordRequest("wrong", "newPass123"),
                        request,
                        new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOfSatisfying(
                        PasswordChangeException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.CURRENT_PASSWORD_MISMATCH));
        verify(transactions).rollback(transaction);
        verify(entity, never()).changePasswordHash(any());
        verify(sessions, never()).expireAllExcept(any(), any());
    }

    // 잠금 대기 중 만료된 세션은 DB 변경 전에 거절한다
    @Test
    void change_whenSessionExpired_doesNotStartTransaction() {
        doThrow(new PasswordChangeException(AuthErrorCode.AUTHENTICATION_REQUIRED))
                .when(sessions)
                .requireActive(any(), any());
        assertThatThrownBy(() -> service.change(
                        current,
                        new ChangePasswordRequest("1234", "newPass123"),
                        request,
                        new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOf(PasswordChangeException.class);
        verifyNoInteractions(transactions, repository);
    }
}
