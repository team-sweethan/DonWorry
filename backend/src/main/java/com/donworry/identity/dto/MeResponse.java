package com.donworry.identity.dto;

import com.donworry.identity.security.SessionUser;
import com.donworry.identity.security.SessionUserMapper;
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
        List<String> permissions,
        boolean passwordChangeRequired) {
    public static MeResponse from(SessionUser user) {
        var summary = SessionUserMapper.toSummary(user);
        return MeResponse.builder()
                .userId(user.userId())
                .loginId(user.loginId())
                .displayName(user.displayName())
                .role(user.role().name())
                .merchantId(user.merchantId())
                .tenantScope(SessionUserMapper.tenantScope(user))
                .permissions(summary.permissions())
                .passwordChangeRequired(user.passwordChangeRequired())
                .build();
    }
}
