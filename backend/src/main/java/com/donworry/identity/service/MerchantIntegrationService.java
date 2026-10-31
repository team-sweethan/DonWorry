package com.donworry.identity.service;

import com.donworry.identity.dto.*;
import com.donworry.identity.dto.IntegrationRequests.*;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.Base64;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class MerchantIntegrationService {
    private final MerchantRepository merchants;
    private final MerchantIntegrationRepository integrations;
    private final MerchantKeyHistoryRepository histories;
    private final PlatformUserRepository users;
    private final MerchantKeyCipher cipher;
    private final LoginAttemptService attempts;
    private final AccountLockService accountLocks;
    private final MerchantLockService merchantLocks;
    private final UserSessionService sessions;
    private final PasswordEncoder passwords;
    private final PlatformTransactionManager transactions;
    private final Clock identityClock;

    public IntegrationResponse owner(SessionUser actor, HttpServletRequest request, int page) {
        SessionUser.require(actor);
        return locked(
                actor,
                actor.merchantId(),
                request,
                false,
                () -> transaction(() -> summary(find(actor.merchantId(), false), page)));
    }

    public IntegrationResponse admin(SessionUser actor, Long merchantId, HttpServletRequest request, int page) {
        return locked(actor, merchantId, request, true, () -> transaction(() -> summary(find(merchantId, true), page)));
    }

    public IntegrationKeyResponse key(SessionUser actor, KeyRequest input, String action, HttpServletRequest request) {
        SessionUser.require(actor);
        return locked(actor, actor.merchantId(), request, false, () -> {
            var user = users.findById(actor.userId()).orElseThrow(() -> failure(AuthErrorCode.AUTHENTICATION_REQUIRED));
            if (attempts.isLocked(user.getLoginLockedUntil())) throw failure(AuthErrorCode.INTEGRATION_PASSWORD_LOCKED);
            if (!passwords.matches(input.currentPassword(), user.getPasswordHash())) {
                attempts.recordFailure(user.getLoginId());
                throw failure(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
            }
            attempts.clearFailures(user.getUserId());
            return transaction(() -> {
                var merchant = find(actor.merchantId(), false);
                var existing = integrations.findByMerchantId(merchant.getMerchantId());
                if (existing.isEmpty() && merchant.getSecretReference() != null)
                    throw new MerchantAuthenticationException(AuthErrorCode.MERCHANT_AUTHENTICATION_UNAVAILABLE);
                var integration = existing.orElseGet(() -> MerchantIntegration.empty(merchant.getMerchantId()));
                if (integration.getKeyVersion() != input.expectedKeyVersion())
                    throw failure(AuthErrorCode.INTEGRATION_CONFLICT);
                if (action.equals("REVEAL")) {
                    if (integration.getStatus() != IntegrationStatus.PENDING_CONNECTION)
                        throw failure(AuthErrorCode.INTEGRATION_KEY_HIDDEN);
                    return new IntegrationKeyResponse(
                            Base64.getEncoder()
                                    .encodeToString(cipher.decrypt(
                                            merchant.getMerchantId(),
                                            integration.getKeyVersion(),
                                            integration.getMasterKeyId(),
                                            integration.getEncryptedSecret(),
                                            integration.getEncryptionNonce())),
                            integration.getKeyVersion());
                }
                if (action.equals("ISSUE")
                        ? integration.getStatus() != IntegrationStatus.NO_KEY
                        : integration.getStatus() == IntegrationStatus.NO_KEY)
                    throw failure(AuthErrorCode.INTEGRATION_CONFLICT);
                byte[] secret = cipher.newSecret(), nonce = cipher.newNonce();
                byte[] encrypted =
                        cipher.encrypt(merchant.getMerchantId(), integration.getKeyVersion() + 1, secret, nonce);
                var now = now();
                integration.issue(encrypted, nonce, cipher.activeId(), now, false);
                integrations.saveAndFlush(integration);
                histories.save(MerchantKeyHistory.record(
                        merchant.getMerchantId(), actor.userId(), action, integration.getKeyVersion(), null, now));
                return new IntegrationKeyResponse(
                        Base64.getEncoder().encodeToString(secret), integration.getKeyVersion());
            });
        });
    }

    public void revoke(SessionUser actor, Long merchantId, RevokeRequest input, HttpServletRequest request) {
        locked(
                actor,
                merchantId,
                request,
                true,
                () -> transaction(() -> {
                    var merchant = find(merchantId, true);
                    var integration = integrations
                            .findByMerchantId(merchantId)
                            .orElseGet(() -> MerchantIntegration.empty(merchantId));
                    if (integration.getKeyVersion() != input.expectedKeyVersion())
                        throw failure(AuthErrorCode.INTEGRATION_CONFLICT);
                    if (integration.getStatus() == IntegrationStatus.NO_KEY && merchant.getSecretReference() == null)
                        throw failure(AuthErrorCode.INTEGRATION_CONFLICT);
                    var now = now();
                    integration.revoke(now);
                    integrations.saveAndFlush(integration);
                    histories.save(MerchantKeyHistory.record(
                            merchantId,
                            actor.userId(),
                            "REVOKE",
                            integration.getKeyVersion(),
                            input.reason().trim(),
                            now));
                    return null;
                }));
    }

    private <T> T locked(
            SessionUser actor, Long merchantId, HttpServletRequest request, boolean admin, Supplier<T> work) {
        SessionUser.require(actor);
        return merchantLocks.withMerchant(
                merchantId,
                () -> accountLocks.withAccount(actor.loginId(), () -> {
                    var session = request.getSession(false);
                    if (session == null) throw failure(AuthErrorCode.AUTHENTICATION_REQUIRED);
                    sessions.requireActive(actor.userId(), session.getId());
                    var user = users.findById(actor.userId())
                            .orElseThrow(() -> failure(AuthErrorCode.AUTHENTICATION_REQUIRED));
                    if (user.getStatus() != UserStatus.ACTIVE) throw failure(AuthErrorCode.AUTHENTICATION_REQUIRED);
                    if (user.isPasswordChangeRequired()) throw failure(AuthErrorCode.PASSWORD_CHANGE_REQUIRED);
                    if (admin
                            ? user.getRole() != UserRole.ADMIN
                            : user.getRole() != UserRole.OPERATOR
                                    || user.getMerchantId() == null
                                    || !user.getMerchantId().equals(merchantId))
                        throw failure(AuthErrorCode.ACCESS_DENIED);
                    return work.get();
                }));
    }

    private Merchant find(Long merchantId, boolean admin) {
        if (merchantId == null || merchantId <= 0) throw failure(AuthErrorCode.INTEGRATION_INVALID_INPUT);
        var merchant =
                merchants.lockById(merchantId).orElseThrow(() -> failure(AuthErrorCode.ADMIN_MERCHANT_NOT_FOUND));
        if (!admin && merchant.getStatus() != MerchantStatus.ACTIVE)
            throw failure(AuthErrorCode.INTEGRATION_MERCHANT_INACTIVE);
        return merchant;
    }

    private IntegrationResponse summary(Merchant merchant, int page) {
        if (page < 0 || page > 1_000_000) throw failure(AuthErrorCode.INTEGRATION_INVALID_INPUT);
        var i = integrations.findByMerchantId(merchant.getMerchantId()).orElse(null);
        var history =
                histories.findByMerchantIdOrderByHistoryIdDesc(merchant.getMerchantId(), PageRequest.of(page, 20));
        return IntegrationResponse.builder()
                .merchantCode(merchant.getMerchantCode())
                .status(i == null ? "NO_KEY" : i.getStatus().name())
                .keyVersion(i == null ? 0 : i.getKeyVersion())
                .keyIssuedAt(i == null ? null : i.getKeyIssuedAt())
                .connectedAt(i == null ? null : i.getConnectedAt())
                .histories(history.stream()
                        .map(h -> IntegrationResponse.History.builder()
                                .historyId(h.getHistoryId())
                                .actorUserId(h.getActorUserId())
                                .action(h.getAction())
                                .keyVersion(h.getKeyVersion())
                                .reason(h.getReason())
                                .createdAt(h.getCreatedAt())
                                .build())
                        .toList())
                .totalElements(history.getTotalElements())
                .build();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC);
    }

    private <T> T transaction(Supplier<T> work) {
        var t = new TransactionTemplate(transactions);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t.execute(s -> work.get());
    }

    private AccountManagementException failure(AuthErrorCode code) {
        return new AccountManagementException(code);
    }
}
