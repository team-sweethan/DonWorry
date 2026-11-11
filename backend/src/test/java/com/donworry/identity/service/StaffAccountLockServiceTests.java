package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.StaffAccountLockException;
import com.donworry.identity.api.StaffAccountLockException.Reason;
import com.donworry.identity.api.StaffLockOutcome;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLockRelease;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.repository.StaffAccountLockRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class StaffAccountLockServiceTests {
    private static final long STAFF = 7L;
    private static final long ADMIN = 1L;
    private static final long ALERT = 31L;
    private static final long CASE = 55L;

    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final StaffAccountLockRepository lockRecords = mock(StaffAccountLockRepository.class);
    private final AdminChangeHistoryService histories = mock(AdminChangeHistoryService.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PlatformUser staff = mock(PlatformUser.class);
    private final PlatformUser admin = mock(PlatformUser.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final StaffAccountLockService service = new StaffAccountLockService(
            users,
            lockRecords,
            histories,
            new AccountLockService(),
            sessions,
            transactions,
            Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(users.findById(STAFF)).thenReturn(Optional.of(staff));
        when(users.findById(ADMIN)).thenReturn(Optional.of(admin));
        when(users.findForUpdateByUserId(STAFF)).thenReturn(Optional.of(staff));
        when(staff.getLoginId()).thenReturn("staff");
        when(staff.getRole()).thenReturn(UserRole.SECURITY_STAFF);
        when(staff.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(admin.getRole()).thenReturn(UserRole.ADMIN);
        when(transactions.getTransaction(any())).thenReturn(transaction);
    }

    // 정지하면 SUSPENDED · 열린 정지 기록 · 시스템 변경 이력을 새 트랜잭션으로 커밋한 뒤 세션을 모두 끊는다
    @Test
    void lock_securityStaff_suspendsStoresLockAndExpiresSessions() {
        service.lockBySystem(STAFF, StaffLockReason.INSIDER_EXPORT_BLOCKED, ALERT);

        var saved = ArgumentCaptor.forClass(StaffAccountLock.class);
        var order = inOrder(lockRecords, staff, histories, transactions, sessions);
        order.verify(lockRecords).saveAndFlush(saved.capture());
        order.verify(staff).changeStatus(UserStatus.SUSPENDED);
        order.verify(histories)
                .recordSystemUser(eq(STAFF), eq("USER_SUSPENDED_BY_INSIDER"), eq(Map.of("status", "ACTIVE")), any());
        order.verify(transactions).commit(transaction);
        order.verify(sessions).expireAll(STAFF);
        verify(transactions)
                .getTransaction(argThat(definition ->
                        definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        var lock = saved.getValue();
        assertThat(lock.getStaffId()).isEqualTo(STAFF);
        assertThat(lock.getStatus()).isEqualTo(StaffAccountLockStatus.OPEN);
        assertThat(lock.getReasonCode()).isEqualTo(StaffLockReason.INSIDER_EXPORT_BLOCKED);
        assertThat(lock.getSourceAlertId()).isEqualTo(ALERT);
        assertThat(lock.getLockedByType()).isEqualTo(AdminChangeActorType.SYSTEM);
        assertThat(lock.getLockedBy()).isNull();
    }

    // 보안 직원이 아닌 계정(관리자 · 사장님)은 직원 정지 경로로 잠그지 않는다
    @Test
    void lock_nonSecurityStaff_isRejected() {
        for (UserRole role : new UserRole[] {UserRole.ADMIN, UserRole.OPERATOR}) {
            when(staff.getRole()).thenReturn(role);
            assertThatThrownBy(() -> service.lockBySystem(STAFF, StaffLockReason.INSIDER_EXPORT_BLOCKED, ALERT))
                    .isInstanceOfSatisfying(
                            StaffAccountLockException.class,
                            e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_SECURITY_STAFF));
        }
        verify(lockRecords, never()).saveAndFlush(any());
        verify(staff, never()).changeStatus(any());
        verifyNoInteractions(histories, sessions);
    }

    // 이미 열린 정지가 있으면 두 번째 정지는 오류 없이 성공하고 기록 · 이력 · 세션 만료를 다시 하지 않는다
    @Test
    void lock_twice_keepsSingleOpenLock() {
        when(staff.getStatus()).thenReturn(UserStatus.SUSPENDED);
        when(lockRecords.existsByStaffIdAndStatus(STAFF, StaffAccountLockStatus.OPEN))
                .thenReturn(true);

        assertThatCode(() -> service.lockBySystem(STAFF, StaffLockReason.INSIDER_EXPORT_BLOCKED, ALERT))
                .doesNotThrowAnyException();

        verify(lockRecords, never()).saveAndFlush(any());
        verify(staff, never()).changeStatus(any());
        verifyNoInteractions(histories, sessions);
    }

    // 무혐의 판정으로 풀면 사용 중으로 돌아가고, 푼 관리자 · 사건 · 판정이 정지 기록과 변경 이력에 남는다
    @Test
    void release_restore_activatesAndStoresAdminCaseDecision() {
        var lock = openLock();
        when(staff.getStatus()).thenReturn(UserStatus.SUSPENDED);

        service.release(release(StaffLockOutcome.RESTORE));

        verify(staff).changeStatus(UserStatus.ACTIVE);
        assertReleased(lock, StaffLockOutcome.RESTORE);
        verify(histories)
                .recordUser(
                        eq(ADMIN),
                        eq(STAFF),
                        eq("USER_RELEASED_BY_INSIDER_DECISION"),
                        eq(Map.of("status", "SUSPENDED")),
                        argThat(after -> after instanceof Map<?, ?> map
                                && CASE == ((Number) map.get("caseId")).longValue()
                                && "CLEARED".equals(map.get("decision"))
                                && "RESTORE".equals(map.get("outcome"))));
    }

    // 징계 판정으로 풀면 바로 비활성이 되어, 풀리는 순간에도 다시 로그인할 수 없다
    @Test
    void release_deactivate_setsInactive() {
        var lock = openLock();
        when(staff.getStatus()).thenReturn(UserStatus.SUSPENDED);

        service.release(release(StaffLockOutcome.DEACTIVATE));

        verify(staff).changeStatus(UserStatus.INACTIVE);
        verify(staff, never()).changeStatus(UserStatus.ACTIVE);
        assertReleased(lock, StaffLockOutcome.DEACTIVATE);
    }

    private StaffAccountLock openLock() {
        var lock = StaffAccountLock.bySystem(
                STAFF, StaffLockReason.INSIDER_EXPORT_BLOCKED, ALERT, Instant.parse("2026-11-07T09:00:00Z"));
        when(lockRecords.findForUpdateByStaffIdAndStatus(STAFF, StaffAccountLockStatus.OPEN))
                .thenReturn(Optional.of(lock));
        return lock;
    }

    private static StaffLockRelease release(StaffLockOutcome outcome) {
        return StaffLockRelease.builder()
                .staffId(STAFF)
                .adminId(ADMIN)
                .caseId(CASE)
                .decision("CLEARED")
                .outcome(outcome)
                .build();
    }

    private static void assertReleased(StaffAccountLock lock, StaffLockOutcome outcome) {
        assertThat(lock.getStatus()).isEqualTo(StaffAccountLockStatus.RELEASED);
        assertThat(lock.getReleasedBy()).isEqualTo(ADMIN);
        assertThat(lock.getReleaseCaseId()).isEqualTo(CASE);
        assertThat(lock.getReleaseDecision()).isEqualTo("CLEARED");
        assertThat(lock.getReleaseOutcome()).isEqualTo(outcome);
        assertThat(lock.getReleasedAt()).isNotNull();
    }
}
