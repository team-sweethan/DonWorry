package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.OperatorAccountStatus;
import com.donworry.identity.entity.AdminChangeHistory;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.AdminChangeHistoryRepository;
import com.donworry.identity.repository.PlatformUserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OperatorAccountStatusReaderServiceTests {
    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final AdminChangeHistoryRepository histories = mock(AdminChangeHistoryRepository.class);
    private final OperatorAccountStatusReaderService reader = new OperatorAccountStatusReaderService(users, histories);

    // 잘못된 ID는 저장소를 조회하지 않는다
    @Test
    void findById_whenIdInvalid_throwsException() {
        for (Long id : new Long[] {null, 0L, -1L}) {
            assertThatIllegalArgumentException().isThrownBy(() -> reader.findById(id));
        }
        verifyNoInteractions(users, histories);
    }

    // 없는 계정은 이력을 조회하지 않고 빈 결과를 반환한다
    @Test
    void findById_whenUserMissing_returnsEmpty() {
        when(users.findById(1L)).thenReturn(Optional.empty());
        assertThat(reader.findById(1L)).isEmpty();
        verifyNoInteractions(histories);
    }

    // 직원은 활성 여부와 관계없이 조회 대상에서 제외한다
    @Test
    void findById_whenStaff_returnsEmpty() {
        for (var role : new UserRole[] {UserRole.ADMIN, UserRole.SECURITY_STAFF}) {
            for (var status : UserStatus.values()) {
                var user = PlatformUser.create("staff", "직원", role, null, "hash");
                user.changeStatus(status);
                when(users.findById(1L)).thenReturn(Optional.of(user));
                assertThat(reader.findById(1L)).isEmpty();
            }
        }
        verifyNoInteractions(histories);
    }

    // 해제 이력이 없는 사장님도 현재 상태를 반환하며 생성 시각을 대신 쓰지 않는다
    @Test
    void findById_whenNoActivationHistory_returnsStatusWithNullActivation() {
        var user = PlatformUser.create("owner", "사장님", UserRole.OPERATOR, 1L, "hash");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(histories.findFirstByTargetUserIdAndChangeTypeOrderByCreatedAtDesc(1L, "USER_ENABLED"))
                .thenReturn(Optional.empty());
        assertThat(reader.findById(1L).orElseThrow().status()).isEqualTo(OperatorAccountStatus.ACTIVE);
        assertThat(reader.findById(1L).orElseThrow().lastActivatedAt()).isNull();
        user.changeStatus(UserStatus.INACTIVE);
        assertThat(reader.findById(1L).orElseThrow().status()).isEqualTo(OperatorAccountStatus.INACTIVE);
    }

    // UTC 저장 시각을 마이크로초까지 보존하고 비활성 계정의 해제 이력도 유지한다
    @Test
    void findById_whenActivationExists_returnsUtcInstantForBothStatuses() {
        var user = PlatformUser.create("owner", "사장님", UserRole.OPERATOR, 1L, "hash");
        var history = mock(AdminChangeHistory.class);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(histories.findFirstByTargetUserIdAndChangeTypeOrderByCreatedAtDesc(1L, "USER_ENABLED"))
                .thenReturn(Optional.of(history));
        when(history.getCreatedAt()).thenReturn(LocalDateTime.parse("2026-11-04T05:00:00.123456"));
        for (var status : UserStatus.values()) {
            user.changeStatus(status);
            var result = reader.findById(1L).orElseThrow();
            assertThat(result.status().name()).isEqualTo(status.name());
            assertThat(result.lastActivatedAt()).isEqualTo(Instant.parse("2026-11-04T05:00:00.123456Z"));
        }
    }

    // 계정 조회 장애를 없는 계정으로 바꾸지 않는다
    @Test
    void findById_whenUserLookupFails_propagatesException() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(users.findById(1L)).thenThrow(failure);
        assertThatThrownBy(() -> reader.findById(1L)).isSameAs(failure);
    }

    // 감사 조회 장애를 해제 이력 없음으로 바꾸지 않는다
    @Test
    void findById_whenHistoryLookupFails_propagatesException() {
        var user = PlatformUser.create("owner", "사장님", UserRole.OPERATOR, 1L, "hash");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var failure = new DataAccessResourceFailureException("history unavailable");
        when(histories.findFirstByTargetUserIdAndChangeTypeOrderByCreatedAtDesc(1L, "USER_ENABLED"))
                .thenThrow(failure);
        assertThatThrownBy(() -> reader.findById(1L)).isSameAs(failure);
    }
}
