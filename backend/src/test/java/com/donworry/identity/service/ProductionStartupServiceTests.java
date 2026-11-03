package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ProductionStartupServiceTests {
    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final AdminChangeHistoryService histories = mock(AdminChangeHistoryService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ProductionStartupService service =
            new ProductionStartupService(users, new PasswordPolicy(), encoder, histories);

    // 환경 비밀번호가 없거나 잘못돼도 입력값 없이 실패하며 DB에 접근하지 않는다
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234", "abcdefgh", "        ", "invalid-secret"})
    void initialize_whenInvalid_failsWithoutReadingDatabase(String password) {
        var error = catchThrowable(() -> service.initialize(password));
        assertThat(error).isInstanceOf(IllegalStateException.class);
        if (password != null && !password.isEmpty())
            assertThat(error.getMessage()).doesNotContain(password);
        assertThat(error.getCause()).isNull();
        verifyNoInteractions(users, histories);
    }

    // 없거나 다른 역할이거나 정지된 관리자를 임의로 만들거나 재활성화하지 않는다
    @Test
    void initialize_whenAdminInvalid_failsWithoutWritingHistory() {
        when(users.findByLoginId("admin")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.initialize("Production123!")).isInstanceOf(IllegalStateException.class);
        for (var role : UserRole.values()) {
            var user = PlatformUser.create("admin", "admin", role, null, encoder.encode("Old12345!"));
            user.changeStatus(UserStatus.INACTIVE);
            when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
            assertThatThrownBy(() -> service.initialize("Production123!")).isInstanceOf(IllegalStateException.class);
            if (role != UserRole.ADMIN) {
                user.changeStatus(UserStatus.ACTIVE);
                assertThatThrownBy(() -> service.initialize("Production123!"))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        verifyNoInteractions(histories);
    }

    // 비밀번호와 강제 변경 상태를 바꾸고 없는 시연 계정은 생성하지 않는다
    @Test
    void initialize_whenChanged_updatesHashAndRecordsSafeHistory() {
        var user = PlatformUser.create("admin", "admin", UserRole.ADMIN, null, encoder.encode("Old12345!"));
        when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
        service.initialize("Production123!");
        assertThat(encoder.matches("Production123!", user.getPasswordHash())).isTrue();
        assertThat(user.isPasswordChangeRequired()).isFalse();
        verify(users).findByLoginId("admin");
        for (String demo : new String[] {"boss", "staff_test", "cakeshop_owner"})
            verify(users).findByLoginId(demo);
        verifyNoMoreInteractions(users);
        verify(histories).recordUser(isNull(), isNull(), eq("PRODUCTION_PASSWORD_APPLIED"), any(), any());
    }

    // 같은 비밀번호로 재시작하면 BCrypt 해시와 이력을 반복해서 만들지 않는다
    @Test
    void initialize_whenAlreadyApplied_preservesHashAndSkipsHistory() {
        var hash = encoder.encode("Production123!");
        var user = PlatformUser.create("admin", "admin", UserRole.ADMIN, null, hash);
        user.changePasswordHash(hash);
        when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
        service.initialize("Production123!");
        assertThat(user.getPasswordHash()).isEqualTo(hash);
        verifyNoInteractions(histories);
    }

    // 비밀번호가 같아도 강제 변경 플래그가 있으면 해제하고 이력을 남긴다
    @Test
    void initialize_whenOnlyFlagChanged_preservesHashAndClearsFlag() {
        var hash = encoder.encode("Production123!");
        var user = PlatformUser.create("admin", "admin", UserRole.ADMIN, null, hash);
        when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
        service.initialize("Production123!");
        assertThat(user.getPasswordHash()).isEqualTo(hash);
        assertThat(user.isPasswordChangeRequired()).isFalse();
        verify(histories).recordUser(isNull(), isNull(), eq("PRODUCTION_PASSWORD_APPLIED"), any(), any());
    }
    // 관리자 비밀번호가 같아도 활성 시연 계정은 정지하고 이미 정지되거나 없는 계정은 건너뛴다
    @Test
    void initialize_whenPasswordUnchanged_inactivatesOnlyActiveDemoAccounts() {
        var hash = encoder.encode("Production123!");
        var admin = PlatformUser.create("admin", "admin", UserRole.ADMIN, null, hash);
        admin.changePasswordHash(hash);
        var boss = PlatformUser.create("boss", "boss", UserRole.OPERATOR, null, "demo-hash");
        var staff = PlatformUser.create("staff_test", "staff", UserRole.SECURITY_STAFF, null, "staff-hash");
        staff.changeStatus(UserStatus.INACTIVE);
        when(users.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(users.findByLoginId("boss")).thenReturn(Optional.of(boss));
        when(users.findByLoginId("staff_test")).thenReturn(Optional.of(staff));
        service.initialize("Production123!");
        assertThat(boss.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(boss.getPasswordHash()).isEqualTo("demo-hash");
        assertThat(boss.isPasswordChangeRequired()).isTrue();
        assertThat(admin.getPasswordHash()).isEqualTo(hash);
        verify(histories)
                .recordUser(
                        isNull(),
                        isNull(),
                        eq("PRODUCTION_DEMO_ACCOUNT_INACTIVATED"),
                        eq(java.util.Map.of("status", "ACTIVE")),
                        eq(java.util.Map.of("status", "INACTIVE", "source", "PRODUCTION_STARTUP")));
        service.initialize("Production123!");
        verifyNoMoreInteractions(histories);
        verify(users, never()).save(any());
    }
}
