package com.donworry.identity.service;

import com.donworry.identity.dto.*;
import com.donworry.identity.dto.ApplicationReviewRequests.*;
import com.donworry.identity.dto.ApplicationReviewResponses.*;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ApplicationReviewService {
    private final MerchantApplicationRepository applications;
    private final PlatformUserRepository users;
    private final MerchantRepository merchants;
    private final UserPermissionRepository permissions;
    private final ApplicationTokenRepository tokens;
    private final ApplicationMailDeliveryRepository deliveries;
    private final MerchantProvisioningService merchantProvisioning;
    private final AccountProvisioningService accountProvisioning;
    private final AdminChangeHistoryService histories;
    private final AccountLockService locks;
    private final UserSessionService sessions;
    private final PasswordEncoder encoder;
    private final ApplicationTokenCodec codec;
    private final ApplicationMailDispatchService dispatch;
    private final ApplicationSettings settings;
    private final PlatformTransactionManager transactionManager;
    private final Clock identityClock;
    private final ApplicationMailQueryService mailQuery;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ApplicationPage list(int page, int size, String keyword, String status) {
        return list(page, size, keyword, status, false);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ApplicationPage list(int page, int size, String keyword, String status, boolean mailAttention) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE || keyword.length() > 100)
            fail(ApplicationErrorCode.INVALID_INPUT);
        ApplicationStatus state;
        try {
            state = status == null ? null : ApplicationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ApplicationException(ApplicationErrorCode.INVALID_INPUT);
        }
        String pattern = "%"
                + keyword.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_") + "%";
        String businessPattern = pattern.replace("-", "").replace(" ", "");
        Specification<MerchantApplication> spec = (root, query, cb) -> {
            var search = cb.or(
                    cb.like(cb.lower(root.get("merchantName")), pattern, '!'),
                    cb.like(root.get("businessRegistrationNumber"), businessPattern, '!'));
            return state == null ? search : cb.and(search, cb.equal(root.get("status"), state));
        };
        var now = now();
        if (mailAttention) {
            Specification<MerchantApplication> attention = (root, query, cb) -> {
                var sub = query.subquery(Long.class);
                var d = sub.from(ApplicationMailDelivery.class);
                var newest = sub.subquery(Long.class);
                var x = newest.from(ApplicationMailDelivery.class);
                newest.select(cb.max(x.<Long>get("deliveryId")))
                        .where(
                                cb.equal(x.get("applicationId"), root.get("applicationId")),
                                cb.equal(x.get("kind"), d.get("kind")));
                var kind = cb.or(
                        cb.and(
                                cb.equal(root.get("status"), ApplicationStatus.EMAIL_PENDING),
                                cb.equal(d.get("kind"), ApplicationMailKind.EMAIL_VERIFICATION)),
                        cb.and(
                                cb.equal(root.get("status"), ApplicationStatus.APPROVED),
                                cb.equal(d.get("kind"), ApplicationMailKind.APPROVAL)),
                        cb.and(
                                cb.equal(root.get("status"), ApplicationStatus.REJECTED),
                                cb.equal(d.get("kind"), ApplicationMailKind.REJECTION)));
                sub.select(d.get("deliveryId"))
                        .where(
                                cb.equal(d.get("applicationId"), root.get("applicationId")),
                                cb.equal(d.get("deliveryId"), newest),
                                kind,
                                cb.or(
                                        d.get("status").in(ApplicationMailStatus.FAILED, ApplicationMailStatus.UNKNOWN),
                                        cb.and(
                                                cb.equal(d.get("status"), ApplicationMailStatus.PENDING),
                                                cb.lessThanOrEqualTo(d.get("startedAt"), now.minusMinutes(5)))));
                return cb.exists(sub);
            };
            spec = spec.and(attention);
        }
        var result = applications.findAll(
                spec,
                PageRequest.of(page, size, Sort.by("createdAt", "applicationId").descending()));
        var mail = mailQuery.summaries(result.getContent(), now);
        return new ApplicationPage(
                result.getContent().stream()
                        .map(a -> Summary.from(a, mail.get(a.getApplicationId())))
                        .toList(),
                page,
                size,
                result.getTotalElements());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(Long id) {
        var a = find(id);
        var u = a.getUserId() == null ? null : users.findById(a.getUserId()).orElse(null);
        String reviewer = a.getReviewerUserId() == null
                ? null
                : users.findById(a.getReviewerUserId())
                        .map(PlatformUser::getDisplayName)
                        .orElse(null);
        var snapshotTime = now();
        var mail = mailQuery.latestItem(a, snapshotTime);
        var availability = mailQuery.availability(a, snapshotTime);
        return Detail.builder()
                .latestMail(mail)
                .resendAllowed(availability.allowed())
                .resendBlockedReason(availability.blockedReason())
                .nextResendAt(availability.nextResendAt())
                .application(Summary.from(a))
                .email(a.getEmail())
                .phone(a.getPhone())
                .inquiry(a.getInquiry())
                .consentVersion(a.getConsentVersion())
                .consentedAt(a.getConsentedAt())
                .reviewedAt(a.getReviewedAt())
                .reviewerUserId(a.getReviewerUserId())
                .reviewerName(reviewer)
                .rejectionReason(a.getRejectionReason())
                .merchantId(a.getMerchantId())
                .requestedLoginId(a.getRequestedLoginId())
                .userId(a.getUserId())
                .loginId(u == null ? null : u.getLoginId())
                .passwordChangeRequired(u == null ? null : u.isPasswordChangeRequired())
                .duplicateMerchants(
                        merchants.findByBusinessRegistrationNumber(a.getBusinessRegistrationNumber()).stream()
                                .filter(m -> !m.getMerchantId().equals(a.getMerchantId()))
                                .map(AdminMerchantResponse::from)
                                .toList())
                .build();
    }

    public ReviewResult approve(SessionUser actor, Long id, ApproveRequest input, HttpServletRequest request) {
        SessionUser.require(actor);
        settings.requireEnabled();
        String requestedLoginId = find(id).getRequestedLoginId();
        if (requestedLoginId == null) fail(ApplicationErrorCode.LOGIN_ID_REQUIRED);
        var prepared = locks.withAccounts(actor.loginId(), requestedLoginId, () -> {
            requireSession(actor, request);
            try {
                return transaction(() -> {
                    requireAdmin(actor);
                    var a = lockPending(id);
                    var now = now();
                    if (input.permissions() == null
                            || input.permissions().size() > 2
                            || !Set.of("FDS_VIEW", "FINANCE_VIEW").containsAll(input.permissions()))
                        fail(ApplicationErrorCode.INVALID_INPUT);
                    var merchant = merchantProvisioning.create(
                            actor,
                            new AdminMerchantRequests.CreateMerchantRequest(
                                    input.merchantCode(), a.getMerchantName(), input.pgFeeRate()));
                    merchant.registerBusinessNumber(a.getBusinessRegistrationNumber());
                    var user = accountProvisioning.createForApplication(
                            actor,
                            new AdminAccountRequests.CreateUserRequest(
                                    a.getRequestedLoginId(), a.getContactName(), "OPERATOR", merchant.getMerchantId()),
                            encoder.encode(codec.generate()),
                            id);
                    var codes = new TreeSet<>(input.permissions());
                    codes.forEach(code -> permissions.save(UserPermission.grant(user.getUserId(), code)));
                    if (!codes.isEmpty())
                        histories.recordUser(
                                actor.userId(),
                                user.getUserId(),
                                "PERMISSIONS_CHANGED",
                                Map.of("permissions", List.of()),
                                Map.of("permissions", codes));
                    a.approve(actor.userId(), merchant.getMerchantId(), user.getUserId(), now);
                    String raw = codec.generate();
                    var token = tokens.saveAndFlush(ApplicationToken.initialPassword(
                            id, user.getUserId(), ApplicationTokenCodec.hash(raw), now));
                    var delivery = deliveries.saveAndFlush(
                            ApplicationMailDelivery.review(id, token.getTokenId(), ApplicationMailKind.APPROVAL, now));
                    return new ReviewMail(
                            delivery.getDeliveryId(),
                            a.getEmail(),
                            user.getLoginId(),
                            raw,
                            null,
                            merchant.getMerchantId(),
                            user.getUserId());
                });
            } catch (DataIntegrityViolationException e) {
                String message = e.getMostSpecificCause().getMessage();
                if (message != null && message.contains("uk_merchants_merchant_code"))
                    throw new AccountManagementException(AuthErrorCode.ADMIN_MERCHANT_CODE_CONFLICT);
                if (message != null && message.contains("uk_platform_users_login_id"))
                    throw new AccountManagementException(AuthErrorCode.ADMIN_LOGIN_ID_CONFLICT);
                throw e;
            }
        });
        var status = dispatch.deliver(
                prepared.deliveryId(),
                () -> dispatch.sender()
                        .sendApproval(
                                prepared.email(), prepared.loginId(), settings.initialPasswordLink(prepared.raw())));
        return new ReviewResult("APPROVED", prepared.merchantId(), prepared.userId(), status.name());
    }

    public ReviewResult reject(SessionUser actor, Long id, RejectRequest input, HttpServletRequest request) {
        SessionUser.require(actor);
        settings.requireEnabled();
        var prepared = locks.withAccount(actor.loginId(), () -> {
            requireSession(actor, request);
            return transaction(() -> {
                requireAdmin(actor);
                var a = lockPending(id);
                if (input.reason() == null
                        || input.reason().isBlank()
                        || input.reason().length() > 1000) fail(ApplicationErrorCode.INVALID_INPUT);
                a.reject(actor.userId(), input.reason().trim(), now());
                var delivery = deliveries.saveAndFlush(
                        ApplicationMailDelivery.review(id, null, ApplicationMailKind.REJECTION, now()));
                return new ReviewMail(
                        delivery.getDeliveryId(), a.getEmail(), null, null, a.getRejectionReason(), null, null);
            });
        });
        var status = dispatch.deliver(
                prepared.deliveryId(), () -> dispatch.sender().sendRejection(prepared.email(), prepared.reason()));
        return new ReviewResult("REJECTED", null, null, status.name());
    }

    private MerchantApplication find(Long id) {
        if (id == null || id <= 0) fail(ApplicationErrorCode.INVALID_INPUT);
        return applications.findById(id).orElseThrow(() -> new ApplicationException(ApplicationErrorCode.NOT_FOUND));
    }

    private MerchantApplication lockPending(Long id) {
        if (id == null || id <= 0) fail(ApplicationErrorCode.INVALID_INPUT);
        var a = applications.lockById(id).orElseThrow(() -> new ApplicationException(ApplicationErrorCode.NOT_FOUND));
        if (a.getStatus() != ApplicationStatus.REVIEW_PENDING || a.getEmailVerifiedAt() == null)
            fail(ApplicationErrorCode.REVIEW_CONFLICT);
        return a;
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

    private <T> T transaction(Supplier<T> work) {
        var t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return t.execute(s -> work.get());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC);
    }

    private void fail(ApplicationErrorCode code) {
        throw new ApplicationException(code);
    }

    private record ReviewMail(
            Long deliveryId, String email, String loginId, String raw, String reason, Long merchantId, Long userId) {
        @Override
        public String toString() {
            return "ReviewMail[redacted]";
        }
    }
}
