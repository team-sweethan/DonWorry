package com.donworry.identity.security;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserStatus;
import java.util.List;
import org.springframework.security.core.userdetails.User;

public class PlatformUserDetails extends User {
    private final SessionUser sessionUser;

    public PlatformUserDetails(PlatformUser user) {
        super(
                user.getLoginId(),
                user.getPasswordHash(),
                user.getStatus() == UserStatus.ACTIVE,
                true,
                true,
                true,
                List.of());
        sessionUser = SessionUser.builder()
                .userId(user.getUserId())
                .loginId(user.getLoginId())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .merchantId(user.getMerchantId())
                .build();
    }

    public SessionUser sessionUser() {
        return sessionUser;
    }
}
