package com.donworry.identity.service;

import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.PasswordChangeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
@RequiredArgsConstructor
public class UserSessionService {
    private final SessionRegistry sessionRegistry;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LogoutHandler sessionLogoutHandler;

    // 단일 서버의 짧은 메모리 작업만 직렬화한다. 비밀번호 검증과 DB 조회는 호출 전에 끝낸다.
    public synchronized void establishAuthentication(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    public synchronized void expireAll(Long userId) {
        expireSessions(userId, null);
    }

    public synchronized void requireActive(Long userId, String sessionId) {
        var session = sessionRegistry.getSessionInformation(sessionId);
        if (session == null || session.isExpired() || !userId.equals(session.getPrincipal()))
            throw new PasswordChangeException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }

    public synchronized void expireAllExcept(Long userId, String retainedSessionId) {
        Assert.hasText(retainedSessionId, "retainedSessionId must not be blank");
        expireSessions(userId, retainedSessionId);
    }

    public synchronized void logoutAll(Long userId, HttpServletRequest request, HttpServletResponse response) {
        expireSessions(userId, null);
        sessionLogoutHandler.logout(
                request, response, SecurityContextHolder.getContext().getAuthentication());
    }

    private void expireSessions(Long userId, String retainedSessionId) {
        Assert.isTrue(userId != null && userId > 0, "userId must be positive");
        for (var session : sessionRegistry.getAllSessions(userId, false)) {
            if (!session.getSessionId().equals(retainedSessionId)) session.expireNow();
        }
    }
}
