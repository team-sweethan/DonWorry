package com.donworry.identity.service;

import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 트랜잭션을 따로 열지 않는다 — 사건 배정은 배정 잠금을 잡은 호출 쪽 트랜잭션 안에서 이 목록을 읽는다
@Service
@RequiredArgsConstructor
public class SecurityStaffDirectoryService implements SecurityStaffDirectory {

    private final PlatformUserRepository users;

    @Override
    public List<SecurityStaffEntry> securityStaff() {
        return users.findByRoleOrderByUserIdAsc(UserRole.SECURITY_STAFF).stream()
                .map(user -> new SecurityStaffEntry(
                        user.getUserId(), user.getDisplayName(), user.getStatus() == UserStatus.ACTIVE))
                .toList();
    }
}
