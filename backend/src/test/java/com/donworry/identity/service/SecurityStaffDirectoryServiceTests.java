package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SecurityStaffDirectoryServiceTests {

    @Mock
    private PlatformUserRepository users;

    // 정지 직원도 목록에 남기고 active=false로 알린다 — 빠지면 지난 사건 담당 이름이 사라지고, true면 정지 직원에게 배정된다
    @Test
    void securityStaff_includesInactiveWithActiveFalse() {
        PlatformUser active = staff(1L, "staff_a", "김가");
        PlatformUser inactive = staff(2L, "staff_b", "이나");
        inactive.changeStatus(UserStatus.INACTIVE);
        given(users.findByRoleOrderByUserIdAsc(UserRole.SECURITY_STAFF)).willReturn(List.of(active, inactive));

        List<SecurityStaffEntry> result = new SecurityStaffDirectoryService(users).securityStaff();

        assertThat(result)
                .containsExactly(new SecurityStaffEntry(1L, "김가", true), new SecurityStaffEntry(2L, "이나", false));
    }

    private static PlatformUser staff(Long userId, String loginId, String displayName) {
        PlatformUser user = PlatformUser.create(loginId, displayName, UserRole.SECURITY_STAFF, null, "hash");
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }
}
