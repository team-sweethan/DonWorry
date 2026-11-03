package com.donworry.identity.security;

import java.util.List;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;

// 권한이나 표시 이름이 달라져도 동일한 계정의 세션으로 관리한다.
public class UserIdSessionRegistry extends SessionRegistryImpl {
    @Override
    public void registerNewSession(String sessionId, Object principal) {
        super.registerNewSession(sessionId, userIdOf(principal));
    }

    @Override
    public List<SessionInformation> getAllSessions(Object principal, boolean includeExpiredSessions) {
        return super.getAllSessions(userIdOf(principal), includeExpiredSessions);
    }

    private Long userIdOf(Object principal) {
        Long userId = switch (principal) {
            case SessionUser user -> user.userId();
            case Long id -> id;
            default -> throw new IllegalArgumentException("Session principal must be a SessionUser or userId");
        };
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return userId;
    }
}
