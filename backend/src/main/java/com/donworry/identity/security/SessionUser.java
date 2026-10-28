package com.donworry.identity.security;

import com.donworry.identity.entity.UserRole;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder(toBuilder = true)
public record SessionUser(
        Long userId,
        String loginId,
        String displayName,
        UserRole role,
        Long merchantId,
        List<String> permissions,
        boolean passwordChangeRequired,
        UUID loginReference)
        implements Serializable {

    // 인가 필터를 지난 뒤 같은 세션의 다른 로그아웃이 공유 SecurityContext의 인증을 비우면
    // @AuthenticationPrincipal이 null로 해석된다. principal을 쓰는 서비스는 첫 줄에서 이 검사를 거친다.
    public static SessionUser require(SessionUser user) {
        if (user == null) throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        return user;
    }
}
