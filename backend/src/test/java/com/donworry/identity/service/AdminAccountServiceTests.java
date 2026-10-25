package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.dto.AdminAccountRequests.SetStatusRequest;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AdminAccountServiceTests {
    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final AdminChangeHistoryService histories = mock(AdminChangeHistoryService.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final PlatformUser actorEntity = mock(PlatformUser.class);
    private final PlatformUser target = mock(PlatformUser.class);
    private final SessionUser actor = SessionUser.builder()
            .userId(1L)
            .loginId("admin")
            .role(UserRole.ADMIN)
            .build();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final AdminAccountService service = new AdminAccountService(
            users,
            mock(UserPermissionRepository.class),
            mock(MerchantRepository.class),
            histories,
            new AccountLockService(),
            new MerchantLockService(),
            sessions,
            mock(PasswordEncoder.class),
            mock(TemporaryPasswordService.class),
            transactions);

    @BeforeEach
    void setUp() {
        request.getSession();
        when(users.findById(1L)).thenReturn(Optional.of(actorEntity));
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(actorEntity.getRole()).thenReturn(UserRole.ADMIN);
        when(actorEntity.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(target.getUserId()).thenReturn(2L);
        when(target.getLoginId()).thenReturn("target");
        when(target.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(transactions.getTransaction(any())).thenReturn(transaction);
    }

    // 이력 INSERT와 실제 커밋을 끝낸 뒤에만 세션을 종료한다
    @Test
    void setStatus_whenValid_commitsHistoryBeforeExpiringSession() {
        service.setStatus(actor, 2L, new SetStatusRequest("DISABLED"), request);
        var order = inOrder(sessions, target, histories, transactions);
        order.verify(sessions).requireActive(1L, request.getSession().getId());
        order.verify(target).changeStatus(UserStatus.DISABLED);
        order.verify(histories).recordUser(anyLong(), anyLong(), anyString(), any(), any());
        order.verify(transactions).commit(transaction);
        order.verify(sessions).expireAll(2L);
        verify(transactions)
                .getTransaction(
                        argThat(d -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    // 커밋 실패는 세션 종료를 실행하지 않는다
    @Test
    void setStatus_whenCommitFails_doesNotExpireSession() {
        doThrow(new TransactionSystemException("commit failure"))
                .when(transactions)
                .commit(transaction);
        assertThatThrownBy(() -> service.setStatus(actor, 2L, new SetStatusRequest("DISABLED"), request))
                .isInstanceOf(TransactionSystemException.class);
        verify(sessions, never()).expireAll(any());
    }

    // 잠금 대기 뒤 수행자 세션이 만료되었으면 DB 변경을 시작하지 않는다
    @Test
    void setStatus_whenActorExpired_doesNotStartTransaction() {
        doThrow(new PasswordChangeException(AuthErrorCode.AUTHENTICATION_REQUIRED))
                .when(sessions)
                .requireActive(any(), any());
        assertThatThrownBy(() -> service.setStatus(actor, 2L, new SetStatusRequest("DISABLED"), request))
                .isInstanceOf(PasswordChangeException.class);
        verifyNoInteractions(transactions, histories);
        verify(target, never()).changeStatus(any());
    }

    // 같은 상태 재요청에는 이력과 세션 종료를 반복하지 않는다
    @Test
    void setStatus_whenUnchanged_doesNotWriteHistoryOrExpireSession() {
        service.setStatus(actor, 2L, new SetStatusRequest("ACTIVE"), request);
        verifyNoInteractions(histories);
        verify(sessions, never()).expireAll(any());
    }
}
