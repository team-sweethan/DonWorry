package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.dto.AdminMerchantRequests.SetMerchantStatusRequest;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AdminMerchantServiceTests {
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final AdminChangeHistoryService histories = mock(AdminChangeHistoryService.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final PlatformUser admin = mock(PlatformUser.class);
    private final Merchant merchant = mock(Merchant.class);
    private final SessionUser actor = SessionUser.builder()
            .userId(1L)
            .loginId("admin")
            .role(UserRole.ADMIN)
            .build();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final AdminMerchantService service = new AdminMerchantService(
            merchants,
            new MerchantProvisioningService(merchants, histories),
            users,
            histories,
            new MerchantLockService(),
            new AccountLockService(),
            sessions,
            transactions);

    @BeforeEach
    void setUp() {
        request.getSession();
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(admin.getRole()).thenReturn(UserRole.ADMIN);
        when(admin.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(merchants.findById(10L)).thenReturn(Optional.of(merchant));
        when(merchant.getStatus()).thenReturn(MerchantStatus.ACTIVE);
        when(transactions.getTransaction(any())).thenReturn(transaction);
        var owner = mock(PlatformUser.class);
        when(owner.getUserId()).thenReturn(2L);
        when(users.findByMerchantIdAndRole(10L, UserRole.OPERATOR)).thenReturn(List.of(owner));
    }

    // 실제 커밋 이후에만 소속 사장님의 세션을 종료한다
    @Test
    void setStatus_whenCommitted_expiresAfterCommit() {
        service.setStatus(actor, 10L, new SetMerchantStatusRequest("INACTIVE"), request);
        var order = inOrder(histories, transactions, sessions);
        order.verify(sessions).requireActive(1L, request.getSession().getId());
        order.verify(histories).recordMerchant(anyLong(), anyLong(), anyString(), any(), any());
        order.verify(transactions).commit(transaction);
        order.verify(sessions).expireAll(2L);
        verify(transactions)
                .getTransaction(
                        argThat(d -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    // DB 커밋 실패 시 기존 세션을 종료하지 않는다
    @Test
    void setStatus_whenCommitFails_preservesSessions() {
        doThrow(new TransactionSystemException("commit failure"))
                .when(transactions)
                .commit(transaction);
        assertThatThrownBy(() -> service.setStatus(actor, 10L, new SetMerchantStatusRequest("INACTIVE"), request))
                .isInstanceOf(TransactionSystemException.class);
        verify(sessions, never()).expireAll(any());
    }

    // 잠금 대기 후 만료된 수행자는 트랜잭션을 시작하지 않는다
    @Test
    void setStatus_whenActorExpired_rejectsBeforeTransaction() {
        doThrow(new PasswordChangeException(AuthErrorCode.AUTHENTICATION_REQUIRED))
                .when(sessions)
                .requireActive(any(), any());
        assertThatThrownBy(() -> service.setStatus(actor, 10L, new SetMerchantStatusRequest("INACTIVE"), request))
                .isInstanceOf(PasswordChangeException.class);
        verifyNoInteractions(transactions, histories);
    }

    // 수행자 DB 상태가 바뀌었으면 오래된 관리자 인증으로 변경할 수 없다
    @Test
    void setStatus_whenActorDisabled_rejectsWithoutMutation() {
        when(admin.getStatus()).thenReturn(UserStatus.DISABLED);
        assertThatThrownBy(() -> service.setStatus(actor, 10L, new SetMerchantStatusRequest("INACTIVE"), request))
                .isInstanceOf(AccountManagementException.class);
        verifyNoInteractions(histories);
        verify(sessions, never()).expireAll(any());
    }

    // 같은 상태 재요청은 이력과 세션 종료를 반복하지 않는다
    @Test
    void setStatus_whenUnchanged_doesNotRepeatEffects() {
        service.setStatus(actor, 10L, new SetMerchantStatusRequest("ACTIVE"), request);
        verifyNoInteractions(histories);
        verify(sessions, never()).expireAll(any());
    }
    // 사전 검사 뒤 발생한 DB UNIQUE 충돌만 코드 중복으로 변환하고 다른 DB 오류는 보존한다
    @Test
    void create_whenInsertFails_mapsOnlyMerchantCodeConstraint() {
        var input = new com.donworry.identity.dto.AdminMerchantRequests.CreateMerchantRequest(
                "CAKE01", "케이크", new java.math.BigDecimal("0.01"));
        var duplicate = new org.springframework.dao.DataIntegrityViolationException(
                "duplicate", new RuntimeException("uk_merchants_merchant_code"));
        when(merchants.saveAndFlush(any())).thenThrow(duplicate);
        assertThatThrownBy(() -> service.create(actor, input, request))
                .isInstanceOfSatisfying(
                        AccountManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.ADMIN_MERCHANT_CODE_CONFLICT));
        var unavailable = new org.springframework.dao.DataIntegrityViolationException("other database constraint");
        doThrow(unavailable).when(merchants).saveAndFlush(any());
        assertThatThrownBy(() -> service.create(actor, input, request)).isSameAs(unavailable);
        verifyNoInteractions(histories);
    }
}
