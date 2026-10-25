package com.donworry.identity.controller;

import com.donworry.identity.security.AccountManagementException;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.PasswordChangeException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.donworry.identity.controller")
public class AuthExceptionHandler {
    @ExceptionHandler(AccountManagementException.class)
    public ProblemDetail accountManagementFailed(AccountManagementException exception) {
        return problem(exception.getErrorCode());
    }

    @ExceptionHandler(PasswordChangeException.class)
    public ProblemDetail passwordChangeFailed(PasswordChangeException exception) {
        return problem(exception.getErrorCode());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ProblemDetail persistenceUnavailable(RuntimeException exception) {
        log.error("identity 요청 DB 처리 오류", exception);
        return problem(AuthErrorCode.PROCESSING_UNAVAILABLE);
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    public ProblemDetail authenticationFailed() {
        return problem(AuthErrorCode.INVALID_CREDENTIALS);
    }

    // 계정 조회 중 DB 오류 등은 Security 필터까지 올라가면 401 AUTH_001로 바뀌므로 여기서 500으로 응답한다.
    @ExceptionHandler(InternalAuthenticationServiceException.class)
    public ProblemDetail authenticationUnavailable() {
        return problem(AuthErrorCode.AUTHENTICATION_UNAVAILABLE);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidInput(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/admin/")) return problem(AuthErrorCode.ADMIN_INVALID_INPUT);
        // 검증 예외의 rejectedValue에는 비밀번호가 포함될 수 있어 응답에 넣지 않는다.
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                request.getRequestURI().equals("/api/auth/password")
                        ? "현재 비밀번호와 새 비밀번호를 확인해 주세요."
                        : "아이디와 비밀번호를 확인해 주세요.");
    }

    private ProblemDetail problem(AuthErrorCode code) {
        var problem = ProblemDetail.forStatusAndDetail(code.getStatus(), code.getMessage());
        problem.setProperty("code", code.getCode());
        return problem;
    }
}
