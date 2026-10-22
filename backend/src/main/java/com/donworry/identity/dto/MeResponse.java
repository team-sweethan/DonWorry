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
                .permissions(admin ? List.of("FDS_VIEW", "FINANCE_VIEW") : List.of())
                .build();
    }
}
