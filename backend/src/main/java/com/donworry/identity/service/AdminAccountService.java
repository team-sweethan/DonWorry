package com.donworry.identity.service;

import com.donworry.identity.dto.*;
import com.donworry.identity.dto.AdminAccountRequests.*;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AdminAccountService {
    private final PlatformUserRepository users;
    private final AccountProvisioningService provisioning;
    private final ApplicationTokenRepository applicationTokens;
    private final UserPermissionRepository permissions;
    private final MerchantRepository merchants;
    private final AdminChangeHistoryService histories;
    private final AccountLockService locks;
    private final MerchantLockService merchantLocks;
    private final UserSessionService sessions;
    private final PasswordEncoder encoder;
    private final TemporaryPasswordService temporaryPasswords;
    private final PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true)
    public AdminUserPageResponse list(
            int page, int size, String keyword, String role, String status, Boolean passwordChangeRequired) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE || keyword.length() > 100)
            invalid();
        UserRole roleFilter = role == null ? null : role(role);
        UserStatus statusFilter = status == null ? null : status(status);
        String pattern = "%"
                + keyword.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_") + "%";
        Specification<PlatformUser> filter = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (roleFilter != null) predicates.add(cb.equal(root.get("role"), roleFilter));
            if (statusFilter != null) predicates.add(cb.equal(root.get("status"), statusFilter));
            if (passwordChangeRequired != null)
                predicates.add(cb.equal(root.get("passwordChangeRequired"), passwordChangeRequired));
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("loginId")), pattern, '!'),
                    cb.like(cb.lower(root.get("displayName")), pattern, '!')));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = users.findAll(
                filter, PageRequest.of(page, size, Sort.by("userId").descending()));
        return new AdminUserPageResponse(responses(result.getContent()), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminUserResponse detail(Long id) {
        return response(find(id));
    }

    @Transactional(readOnly = true)
    public List<MerchantOptionResponse> merchantOptions(String keyword) {
        if (keyword.length() > 100) invalid();
        return merchants
                .findByStatusAndDisplayNameContainingIgnoreCaseOrderByMerchantId(
                        MerchantStatus.ACTIVE, keyword.trim(), PageRequest.of(0, 50))
                .stream()
                .map(m -> new MerchantOptionResponse(m.getMerchantId(), m.getDisplayName()))
                .toList();
    }

    public TemporaryPasswordResponse create(SessionUser actor, CreateUserRequest input, HttpServletRequest request) {
        SessionUser.require(actor);
        return merchantLocks.withMerchant(
                input.merchantId(),
                () -> locks.withAccounts(actor.loginId(), input.loginId(), () -> {
                    requireSession(actor, request);
                    try {
                        return transaction(() -> {
                            requireAdmin(actor);
                            String password = temporaryPasswords.generate(input.loginId());
                            var user = provisioning.create(actor, input, encoder.encode(password));
                            return new TemporaryPasswordResponse(response(user), password);
                        });
                    } catch (DataIntegrityViolationException exception) {
                        String cause = exception.getMostSpecificCause().getMessage();
                        if (cause != null && cause.contains("uk_platform_users_login_id"))
                            throw new AccountManagementException(AuthErrorCode.ADMIN_LOGIN_ID_CONFLICT);
                        throw exception;
                    }
                }));
    }

    public void setPermissions(SessionUser actor, Long id, SetPermissionsRequest input, HttpServletRequest request) {
        withTarget(actor, id, request, user -> {
            if (user.getRole() != UserRole.OPERATOR)
                throw new AccountManagementException(AuthErrorCode.ADMIN_OPERATOR_PERMISSIONS_ONLY);
            var next = new TreeSet<>(input.permissions());
            if (!Set.of("FDS_VIEW", "FINANCE_VIEW").containsAll(next)) invalid();
            var previous = new TreeSet<>(permissions.findPermissionCodesByUserId(id));
            if (next.equals(previous)) return false;
            permissions.deleteByUserId(id);
            // 삭제를 먼저 DB에 반영해 기존 UNIQUE와 새 INSERT가 충돌하지 않게 한다.
            permissions.flush();
            for (String code : next) permissions.save(UserPermission.grant(id, code));
            record(actor, user, "PERMISSIONS_CHANGED", Map.of("permissions", previous), Map.of("permissions", next));
            return true;
        });
    }

    public void setStatus(SessionUser actor, Long id, SetStatusRequest input, HttpServletRequest request) {
        withTarget(actor, id, request, user -> {
            UserStatus next = status(input.status());
            if (actor.userId().equals(id) && next == UserStatus.DISABLED)
                throw new AccountManagementException(AuthErrorCode.ADMIN_SELF_DISABLE);
            if (user.getStatus() == next) return false;
            String previous = user.getStatus().name();
            user.changeStatus(next);
            record(
                    actor,
                    user,
                    next == UserStatus.DISABLED ? "USER_DISABLED" : "USER_ENABLED",
                    Map.of("status", previous),
                    Map.of("status", next.name()));
            return true;
        });
    }

    public TemporaryPasswordResponse resetPassword(SessionUser actor, Long id, HttpServletRequest request) {
        SessionUser.require(actor);
        String loginId = find(id).getLoginId();
        return locks.withAccounts(actor.loginId(), loginId, () -> {
            requireSession(actor, request);
            var result = transaction(() -> {
                requireAdmin(actor);
                var user = find(id);
                boolean previous = user.isPasswordChangeRequired();
                String password = temporaryPasswords.generate(user.getLoginId());
                user.resetPasswordHash(encoder.encode(password));
                applicationTokens
                        .findByUserIdAndPurpose(id, ApplicationTokenPurpose.INITIAL_PASSWORD_SETUP)
                        .forEach(t -> t.revoke(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
                record(
                        actor,
                        user,
                        "PASSWORD_RESET",
                        Map.of("passwordChangeRequired", previous),
                        Map.of("passwordChangeRequired", true));
                return new TemporaryPasswordResponse(response(user), password);
            });
            sessions.expireAll(id);
            return result;
        });
    }

    private void withTarget(
            SessionUser actor, Long id, HttpServletRequest request, Function<PlatformUser, Boolean> change) {
        SessionUser.require(actor);
        String loginId = find(id).getLoginId();
        locks.withAccounts(actor.loginId(), loginId, () -> {
            requireSession(actor, request);
            boolean changed = transaction(() -> {
                requireAdmin(actor);
                return change.apply(find(id));
            });
            if (changed) sessions.expireAll(id);
            return null;
        });
    }

    private void requireSession(SessionUser actor, HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        sessions.requireActive(actor.userId(), session.getId());
    }

    private void requireAdmin(SessionUser actor) {
        var current = users.findById(actor.userId())
                .orElseThrow(() -> new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED));
        if (current.getStatus() != UserStatus.ACTIVE)
            throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        if (current.getRole() != UserRole.ADMIN) throw new AccountManagementException(AuthErrorCode.ACCESS_DENIED);
        if (current.isPasswordChangeRequired())
            throw new AccountManagementException(AuthErrorCode.PASSWORD_CHANGE_REQUIRED);
    }

    private PlatformUser find(Long id) {
        if (id == null || id <= 0) invalid();
        return users.findById(id).orElseThrow(() -> new AccountManagementException(AuthErrorCode.ADMIN_USER_NOT_FOUND));
    }

    private AdminUserResponse response(PlatformUser user) {
        return responses(List.of(user)).getFirst();
    }

    // 목록의 가맹점 이름과 권한은 계정마다 따로 조회하지 않고 한 번씩 모아 읽는다.
    private List<AdminUserResponse> responses(List<PlatformUser> page) {
        if (page.isEmpty()) return List.of();
        var merchantIds = page.stream()
                .map(PlatformUser::getMerchantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> merchantNames = new HashMap<>();
        if (!merchantIds.isEmpty())
            merchants
                    .findByMerchantIdIn(merchantIds)
                    .forEach(m -> merchantNames.put(m.getMerchantId(), m.getDisplayName()));
        Map<Long, List<String>> codes = new HashMap<>();
        permissions
                .findByUserIdInOrderByPermissionCode(
                        page.stream().map(PlatformUser::getUserId).toList())
                .forEach(p -> codes.computeIfAbsent(p.getUserId(), key -> new ArrayList<>())
                        .add(p.getPermissionCode()));
        return page.stream()
                .map(user -> response(
                        user,
                        merchantNames.get(user.getMerchantId()),
                        user.getRole() == UserRole.ADMIN
                                ? SessionUserMapper.ADMIN_PERMISSIONS
                                : List.copyOf(codes.getOrDefault(user.getUserId(), List.of()))))
                .toList();
    }

    private AdminUserResponse response(PlatformUser user, String merchantName, List<String> codes) {
        return AdminUserResponse.builder()
                .userId(user.getUserId())
                .loginId(user.getLoginId())
                .displayName(user.getDisplayName())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .merchantId(user.getMerchantId())
                .merchantName(merchantName)
                .permissions(codes)
                .passwordChangeRequired(user.isPasswordChangeRequired())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private void record(SessionUser actor, PlatformUser user, String type, Object before, Object after) {
        histories.recordUser(actor.userId(), user.getUserId(), type, before, after);
    }

    private <T> T transaction(Supplier<T> work) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private UserRole role(String value) {
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            invalid();
            return null;
        }
    }

    private UserStatus status(String value) {
        try {
            return UserStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            invalid();
            return null;
        }
    }

    private void invalid() {
        throw new AccountManagementException(AuthErrorCode.ADMIN_INVALID_INPUT);
    }
}
