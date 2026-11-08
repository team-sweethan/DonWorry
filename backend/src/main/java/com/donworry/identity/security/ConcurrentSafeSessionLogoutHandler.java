package com.donworry.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

// 같은 세션의 로그아웃이 겹치면 늦은 요청의 invalidate()가 "이미 무효화됨" 예외로 500이 된다 (#610).
// 세션이 이미 사라졌을 때만 로그아웃 완료로 보고, 세션이 살아 있으면 로그아웃 실패를 숨기지 않도록 다시 던진다.
final class ConcurrentSafeSessionLogoutHandler implements LogoutHandler {
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        var session = request.getSession(false);
        if (session == null) return;
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            if (request.getSession(false) != null) throw alreadyInvalidated;
        }
    }
}
