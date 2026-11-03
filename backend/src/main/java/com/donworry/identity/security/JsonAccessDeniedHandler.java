package com.donworry.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityProblemResponseWriter responseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        responseWriter.write(
                response,
                exception instanceof CsrfException ? AuthErrorCode.CSRF_INVALID : AuthErrorCode.ACCESS_DENIED);
    }
}
