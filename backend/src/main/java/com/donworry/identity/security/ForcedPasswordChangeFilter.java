package com.donworry.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
class ForcedPasswordChangeFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED = Set.of(
            "/api/auth/login",
            "/api/auth/me",
            "/api/auth/password",
            "/api/auth/logout",
            "/api/auth/logout-all",
            "/api/auth/csrf",
            "/api/merchant-applications",
            "/api/merchant-applications/initial-password-setups",
            "/api/merchant-applications/email-verifications",
            "/api/merchant-applications/email-verification-resends");
    private final SecurityProblemResponseWriter writer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (path.startsWith("/api/")
                && !ALLOWED.contains(path)
                && authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionUser user
                && user.passwordChangeRequired()) {
            writer.write(response, AuthErrorCode.PASSWORD_CHANGE_REQUIRED);
            return;
        }
        chain.doFilter(request, response);
    }
}
