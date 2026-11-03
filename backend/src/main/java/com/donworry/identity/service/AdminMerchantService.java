package com.donworry.identity.service;

import com.donworry.identity.dto.*;
import com.donworry.identity.dto.AdminMerchantRequests.*;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AdminMerchantService {
    private final MerchantRepository merchants;
    private final MerchantProvisioningService provisioning;
    private final PlatformUserRepository users;
    private final AdminChangeHistoryService histories;
    private final MerchantLockService merchantLocks;
    private final AccountLockService accountLocks;
    private final UserSessionService sessions;
    private final PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true)
    public AdminMerchantPageResponse list(int page, int size, String keyword, String status) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE || keyword.length() > 100)
            invalid();
        MerchantStatus filter = status == null ? null : status(status);
        String pattern = "%"
                + keyword.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_") + "%";
        Specification<Merchant> spec = (root, query, cb) -> {
            var search = cb.or(
                    cb.like(cb.lower(root.get("merchantCode")), pattern, '!'),
                    cb.like(cb.lower(root.get("displayName")), pattern, '!'));
            return filter == null ? search : cb.and(search, cb.equal(root.get("status"), filter));
        };
        var result = merchants.findAll(
                spec, PageRequest.of(page, size, Sort.by("merchantId").descending()));
        return new AdminMerchantPageResponse(
                result.getContent().stream().map(AdminMerchantResponse::from).toList(),
                page,
                size,
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminMerchantResponse detail(Long id) {
        return AdminMerchantResponse.from(find(id));
    }

    public AdminMerchantResponse create(SessionUser actor, CreateMerchantRequest input, HttpServletRequest request) {
        SessionUser.require(actor);
        return accountLocks.withAccount(actor.loginId(), () -> {
            requireSession(actor, request);
            try {
                return transaction(() -> {
                    requireAdmin(actor);
                    var merchant = provisioning.create(actor, input);
                    return AdminMerchantResponse.from(merchant);
                });
            } catch (DataIntegrityViolationException exception) {
                String cause = exception.getMostSpecificCause().getMessage();
                if (cause != null && cause.contains("uk_merchants_merchant_code"))
                    throw new AccountManagementException(AuthErrorCode.ADMIN_MERCHANT_CODE_CONFLICT);
                throw exception;
            }
        });
    }

    public void setStatus(SessionUser actor, Long id, SetMerchantStatusRequest input, HttpServletRequest request) {
        SessionUser.require(actor);
        if (id == null || id <= 0) invalid();
        merchantLocks.withMerchant(
                id,
                () -> accountLocks.withAccount(actor.loginId(), () -> {
                    requireSession(actor, request);
                    List<Long> affectedUsers = transaction(() -> {
                        requireAdmin(actor);
                        var merchant = find(id);
                        var next = status(input.status());
                        if (merchant.getStatus() == next) return List.<Long>of();
                        String previous = merchant.getStatus().name();
                        merchant.changeStatus(next);
                        histories.recordMerchant(
                                actor.userId(),
                                id,
                                next == MerchantStatus.INACTIVE ? "MERCHANT_DISABLED" : "MERCHANT_ENABLED",
                                Map.of("status", previous),
                                Map.of("status", next.name()));
                        return next == MerchantStatus.INACTIVE
                                ? users.findByMerchantIdAndRole(id, UserRole.OPERATOR).stream()
                                        .map(PlatformUser::getUserId)
                                        .toList()
                                : List.<Long>of();
                    });
                    affectedUsers.forEach(sessions::expireAll);
                    return null;
                }));
    }

    private Merchant find(Long id) {
        if (id == null || id <= 0) invalid();
        return merchants
                .findById(id)
                .orElseThrow(() -> new AccountManagementException(AuthErrorCode.ADMIN_MERCHANT_NOT_FOUND));
    }

    private void requireSession(SessionUser actor, HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        sessions.requireActive(actor.userId(), session.getId());
    }

    private void requireAdmin(SessionUser actor) {
        var user = users.findById(actor.userId())
                .orElseThrow(() -> new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED));
        if (user.getStatus() != UserStatus.ACTIVE)
            throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        if (user.getRole() != UserRole.ADMIN) throw new AccountManagementException(AuthErrorCode.ACCESS_DENIED);
        if (user.isPasswordChangeRequired())
            throw new AccountManagementException(AuthErrorCode.PASSWORD_CHANGE_REQUIRED);
    }

    private MerchantStatus status(String value) {
        try {
            return MerchantStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            invalid();
            return null;
        }
    }

    private <T> T transaction(Supplier<T> work) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private void invalid() {
        throw new AccountManagementException(AuthErrorCode.ADMIN_MERCHANT_INVALID_INPUT);
    }
}
