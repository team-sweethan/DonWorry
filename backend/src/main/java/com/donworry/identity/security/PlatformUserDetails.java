package com.donworry.identity.security;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import org.springframework.security.core.userdetails.User;

public class PlatformUserDetails extends User {
    private final SessionUser sessionUser;

    @Getter
    private final LocalDateTime loginLockedUntil;

    public PlatformUserDetails(PlatformUser user) {
        super(
                user.getLoginId(),
                user.getPasswordHash(),
                user.getStatus() == UserStatus.ACTIVE,
                true,
                true,
                true,
                List.of());
        loginLockedUntil = user.getLoginLockedUntil();
        sessionUser = SessionUser.builder()
                .userId(user.getUserId())
                .loginId(user.getLoginId())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .merchantId(user.getMerchantId())
                .passwordChangeRequired(user.isPasswordChangeRequired())
                // 권한은 비밀번호 검증 뒤 AuthService가 채운다
                .permissions(List.of())
                .build();
    }

    public SessionUser sessionUser() {
        return sessionUser;
    }
}
