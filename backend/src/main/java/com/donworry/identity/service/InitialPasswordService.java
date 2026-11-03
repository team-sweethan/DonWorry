package com.donworry.identity.service;

import com.donworry.identity.dto.ApplicationReviewRequests.SetupRequest;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class InitialPasswordService {
    private final ApplicationTokenRepository tokens;
    private final MerchantApplicationRepository applications;
    private final PlatformUserRepository users;
    private final MerchantRepository merchants;
    private final MerchantLockService merchantLocks;
    private final AccountLockService accountLocks;
    private final UserSessionService sessions;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final ApplicationSettings settings;
    private final PlatformTransactionManager transactionManager;
    private final Clock identityClock;
    private final EntityManager entityManager;

    public void setup(SetupRequest input) {
        settings.requireEnabled();
        if (input.token() == null || !input.token().matches("[A-Za-z0-9_-]{43}"))
            fail(ApplicationErrorCode.INVALID_SETUP_TOKEN);
        var hint = tokens.findByTokenHash(ApplicationTokenCodec.hash(input.token()))
                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.INVALID_SETUP_TOKEN));
        if (hint.getPurpose() != ApplicationTokenPurpose.INITIAL_PASSWORD_SETUP || hint.getUserId() == null)
            fail(ApplicationErrorCode.INVALID_SETUP_TOKEN);
        var user = users.findById(hint.getUserId())
                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.INVALID_SETUP_TOKEN));
        merchantLocks.withMerchant(
                user.getMerchantId(),
                () -> accountLocks.withAccount(user.getLoginId(), () -> {
                    var transaction = new TransactionTemplate(transactionManager);
                    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    transaction.executeWithoutResult(s -> {
                        var a = applications
                                .lockById(hint.getApplicationId())
                                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.INVALID_SETUP_TOKEN));
                        var token = tokens.findById(hint.getTokenId())
                                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.INVALID_SETUP_TOKEN));
                        entityManager.refresh(token, LockModeType.PESSIMISTIC_WRITE);
                        if (token.getPurpose() != ApplicationTokenPurpose.INITIAL_PASSWORD_SETUP)
                            fail(ApplicationErrorCode.INVALID_SETUP_TOKEN);
                        if (token.getUsedAt() != null) fail(ApplicationErrorCode.USED_SETUP_TOKEN);
                        if (token.getRevokedAt() != null) fail(ApplicationErrorCode.REVOKED_SETUP_TOKEN);
                        var now = LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC);
                        if (!token.getExpiresAt().isAfter(now)) fail(ApplicationErrorCode.EXPIRED_SETUP_TOKEN);
                        var current = users.findById(token.getUserId())
                                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.INVALID_SETUP_TOKEN));
                        var merchant = merchants
                                .findById(current.getMerchantId())
                                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.SETUP_UNAVAILABLE));
                        if (a.getStatus() != ApplicationStatus.APPROVED
                                || !current.getUserId().equals(a.getUserId())
                                || !merchant.getMerchantId().equals(a.getMerchantId())
                                || current.getRole() != UserRole.OPERATOR
                                || current.getStatus() != UserStatus.ACTIVE
                                || merchant.getStatus() != MerchantStatus.ACTIVE
                                || !current.isPasswordChangeRequired()) fail(ApplicationErrorCode.SETUP_UNAVAILABLE);
                        policy.validate(current.getLoginId(), input.newPassword());
                        current.changePasswordHash(encoder.encode(input.newPassword()));
                        token.use(now);
                    });
                    sessions.expireAll(user.getUserId());
                    return null;
                }));
    }

    private void fail(ApplicationErrorCode code) {
        throw new ApplicationException(code);
    }
}
