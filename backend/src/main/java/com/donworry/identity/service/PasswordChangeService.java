package com.donworry.identity.service;

import com.donworry.identity.dto.ChangePasswordRequest;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.PasswordChangeException;
import com.donworry.identity.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordChangeService {
    private final PlatformUserRepository repository;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AccountLockService accountLockService;
    private final UserSessionService sessions;
    private final PlatformTransactionManager transactionManager;

    public void change(
            SessionUser current,
            ChangePasswordRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        accountLockService.withAccount(current.loginId(), () -> {
            var session = request.getSession(false);
            if (session == null) throw new PasswordChangeException(AuthErrorCode.AUTHENTICATION_REQUIRED);
            String sessionId = session.getId();
            sessions.requireActive(current.userId(), sessionId);
            // 잠금은 실제 커밋과 세션 만료까지 유지한다. 외부 트랜잭션에 합류하면 커밋 전에 종료될 수 있다.
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(status -> {
                var user = repository
                        .findById(current.userId())
                        .orElseThrow(() -> new PasswordChangeException(AuthErrorCode.AUTHENTICATION_REQUIRED));
                if (user.getStatus() != UserStatus.ACTIVE)
                    throw new PasswordChangeException(AuthErrorCode.AUTHENTICATION_REQUIRED);
                if (!encoder.matches(input.currentPassword(), user.getPasswordHash())) {
                    log.info("비밀번호 변경 실패 userId={} code={}", current.userId(), "AUTH_003");
                    throw new PasswordChangeException(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
                }
                try {
                    policy.validate(user.getLoginId(), input.newPassword());
                } catch (PasswordChangeException exception) {
                    log.info("비밀번호 변경 실패 userId={} code={}", current.userId(), "AUTH_006");
                    throw exception;
                }
                user.changePasswordHash(encoder.encode(input.newPassword()));
            });
            sessions.expireAllExcept(current.userId(), sessionId);
            if (current.passwordChangeRequired()) sessions.clearPasswordChangeRequired(current, request, response);
            log.info("비밀번호 변경 성공 userId={}", current.userId());
            return null;
        });
    }
}
