package com.donworry.identity.security;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.OperatorAccessDenialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityProblemResponseWriter responseWriter;
    private final OperatorAccessDenialService denials;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        // 서비스에서 처리되지 않고 이 핸들러까지 올라온 AccessDeniedException도 기록 범위다.
        if (!(exception instanceof CsrfException)) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof SessionUser user
                    && user.role() == UserRole.OPERATOR) {
                try {
                    denials.record(user.userId(), OperatorAccessRoutes.deniedType(request), request);
                } catch (RuntimeException failure) {
                    // DB 예외에는 IP·경로가 들어갈 수 있어 원문·stack trace를 남기지 않는다.
                    log.error(
                            "사장님 403 기록 실패 userId={} errorType={}",
                            user.userId(),
                            failure.getClass().getSimpleName());
                }
            }
        }
        responseWriter.write(
                response,
                exception instanceof CsrfException ? AuthErrorCode.CSRF_INVALID : AuthErrorCode.ACCESS_DENIED);
    }
}
