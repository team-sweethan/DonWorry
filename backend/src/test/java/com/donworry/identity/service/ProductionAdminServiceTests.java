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

class ProductionAdminServiceTests {
    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final AdminChangeHistoryService histories = mock(AdminChangeHistoryService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ProductionAdminService service =
            new ProductionAdminService(users, new PasswordPolicy(), encoder, histories);

    // 환경 비밀번호가 없거나 잘못돼도 입력값 없이 실패하며 DB에 접근하지 않는다
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234", "abcdefgh", "        ", "invalid-secret"})
    void applyPassword_whenInvalid_failsWithoutReadingDatabase(String password) {
        var error = catchThrowable(() -> service.applyPassword(password));
        assertThat(error).isInstanceOf(IllegalStateException.class);
        if (password != null && !password.isEmpty())
            assertThat(error.getMessage()).doesNotContain(password);
        assertThat(error.getCause()).isNull();
        verifyNoInteractions(users, histories);
    }

    // 없거나 다른 역할이거나 정지된 관리자를 임의로 만들거나 재활성화하지 않는다
    @Test
    void applyPassword_whenAdminInvalid_failsWithoutWritingHistory() {
        when(users.findByLoginId("admin")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.applyPassword("Production123!")).isInstanceOf(IllegalStateException.class);
        for (var role : UserRole.values()) {
            var user = PlatformUser.create("admin", "admin", role, null, encoder.encode("Old12345!"));
            user.changeStatus(UserStatus.DISABLED);
            when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
            assertThatThrownBy(() -> service.applyPassword("Production123!")).isInstanceOf(IllegalStateException.class);
            if (role != UserRole.ADMIN) {
                user.changeStatus(UserStatus.ACTIVE);
                assertThatThrownBy(() -> service.applyPassword("Production123!"))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        verifyNoInteractions(histories);
    }

    // 비밀번호와 강제 변경 상태만 바꾸고 시연 계정은 조회하지 않는다
    @Test
    void applyPassword_whenChanged_updatesHashAndRecordsSafeHistory() {
        var user = PlatformUser.create("admin", "admin", UserRole.ADMIN, null, encoder.encode("Old12345!"));
        when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
        service.applyPassword("Production123!");
        assertThat(encoder.matches("Production123!", user.getPasswordHash())).isTrue();
        assertThat(user.isPasswordChangeRequired()).isFalse();
        verify(users).findByLoginId("admin");
        verifyNoMoreInteractions(users);
        verify(histories).recordUser(isNull(), isNull(), eq("PRODUCTION_PASSWORD_APPLIED"), any(), any());
    }

    // 같은 비밀번호로 재시작하면 BCrypt 해시와 이력을 반복해서 만들지 않는다
    @Test
    void applyPassword_whenAlreadyApplied_preservesHashAndSkipsHistory() {
        var hash = encoder.encode("Production123!");
        var user = PlatformUser.create("admin", "admin", UserRole.ADMIN, null, hash);
        user.changePasswordHash(hash);
        when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
        service.applyPassword("Production123!");
        assertThat(user.getPasswordHash()).isEqualTo(hash);
        verifyNoInteractions(histories);
    }

    // 비밀번호가 같아도 강제 변경 플래그가 있으면 해제하고 이력을 남긴다
    @Test
    void applyPassword_whenOnlyFlagChanged_preservesHashAndClearsFlag() {
        var hash = encoder.encode("Production123!");
        var user = PlatformUser.create("admin", "admin", UserRole.ADMIN, null, hash);
        when(users.findByLoginId("admin")).thenReturn(Optional.of(user));
        service.applyPassword("Production123!");
        assertThat(user.getPasswordHash()).isEqualTo(hash);
        assertThat(user.isPasswordChangeRequired()).isFalse();
        verify(histories).recordUser(isNull(), isNull(), eq("PRODUCTION_PASSWORD_APPLIED"), any(), any());
    }
}
