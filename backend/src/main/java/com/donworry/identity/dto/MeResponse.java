package com.donworry.identity.dto;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.security.SessionUser;
import java.util.List;
import lombok.Builder;

@Builder
public record MeResponse(
        Long userId,
        String loginId,
        String displayName,
        String role,
        Long merchantId,
        String tenantScope,
        List<String> permissions) {
    public static MeResponse from(SessionUser user) {
        boolean admin = user.role() == UserRole.ADMIN;
        return MeResponse.builder()
                .userId(user.userId())
                .loginId(user.loginId())
                .displayName(user.displayName())
                .role(user.role().name())
                .merchantId(user.merchantId())
                .tenantScope(admin && user.merchantId() == null ? "ALL" : null)
                // ADMIN 조회 권한은 role로 계산하고, 나머지는 로그인 시 읽은 user_permissions를 쓴다 (Issue #70)
                // 권한 필드가 없던 이전 세션은 권한 없음으로 본다
                .permissions(
                        admin
                                ? List.of("FDS_VIEW", "FINANCE_VIEW")
                                : user.permissions() == null ? List.of() : user.permissions())
                .build();
    }
}
