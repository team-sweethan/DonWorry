package com.donworry.identity.service;

import com.donworry.identity.dto.ApplicationMailResponses.*;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ApplicationMailRecoveryService {
    private final MerchantApplicationRepository applications;
    private final PlatformUserRepository users;
    private final ApplicationTokenRepository tokens;
    private final ApplicationMailDeliveryRepository deliveries;
    private final ApplicationMailQueryService query;
    private final ApplicationRateLimitService limits;
    private final ApplicationSettings settings;
    private final ApplicationTokenCodec codec;
    private final ApplicationMailDispatchService dispatch;
    private final AccountLockService accountLocks;
    private final MerchantLockService merchantLocks;
    private final UserSessionService sessions;
    private final PlatformTransactionManager transactions;

    public ResendResult resend(SessionUser actor, Long id, Long expected, HttpServletRequest request) {
        SessionUser.require(actor);
        settings.requireEnabled();
        if (id == null || id <= 0 || expected == null || expected <= 0)
            throw new ApplicationException(ApplicationErrorCode.INVALID_REVIEW_INPUT);
        var hint =
                applications.findById(id).orElseThrow(() -> new ApplicationException(ApplicationErrorCode.NOT_FOUND));
        // 제한 소비 전에도 수행자·현재 발송을 확인하고 최종 트랜잭션에서 다시 검사한다.
        accountLocks.withAccount(actor.loginId(), () -> {
            requireActor(actor, request);
            check(hint, expected);
            return null;
        });
        if (hint.getStatus() == ApplicationStatus.EMAIL_PENDING) limits.adminResend(hint.getEmail());
        var target = hint.getUserId() == null
                ? null
                : users.findById(hint.getUserId()).orElse(null);
        Supplier<Prepared> work = () -> accountLocks.withAccounts(
                actor.loginId(), target == null ? actor.loginId() : target.getLoginId(), () -> {
                    requireActor(actor, request);
                    var tx = new TransactionTemplate(transactions);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    return tx.execute(s -> {
                        requireActor(actor, request);
                        var a = applications
                                .lockById(id)
                                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.NOT_FOUND));
                        check(a, expected, a.getStatus() == ApplicationStatus.EMAIL_PENDING);
                        var kind = ApplicationMailQueryService.kind(a.getStatus());
                        var now = query.now();
                        String raw = null;
                        Long tokenId = null;
                        if (kind != ApplicationMailKind.REJECTION) {
                            var purpose = kind == ApplicationMailKind.APPROVAL
                                    ? ApplicationTokenPurpose.INITIAL_PASSWORD_SETUP
                                    : ApplicationTokenPurpose.EMAIL_VERIFICATION;
                            tokens.findByApplicationIdAndPurpose(id, purpose).forEach(t -> t.revoke(now));
                            raw = codec.generate();
                            var token = kind == ApplicationMailKind.APPROVAL
                                    ? ApplicationToken.initialPassword(
                                            id, a.getUserId(), ApplicationTokenCodec.hash(raw), now)
                                    : ApplicationToken.emailVerification(id, ApplicationTokenCodec.hash(raw), now);
                            tokenId = tokens.saveAndFlush(token).getTokenId();
                        }
                        var d = deliveries.saveAndFlush(ApplicationMailDelivery.review(id, tokenId, kind, now));
                        return new Prepared(
                                d.getDeliveryId(),
                                kind,
                                a.getEmail(),
                                target == null ? null : target.getLoginId(),
                                raw,
                                a.getRejectionReason());
                    });
                });
        var prepared = hint.getStatus() == ApplicationStatus.APPROVED
                ? merchantLocks.withMerchant(hint.getMerchantId(), work)
                : work.get();
        var status = dispatch.deliver(prepared.id(), () -> {
            switch (prepared.kind()) {
                case EMAIL_VERIFICATION ->
                    dispatch.sender().sendVerification(prepared.email(), settings.verificationLink(prepared.raw()));
                case APPROVAL ->
                    dispatch.sender()
                            .sendApproval(
                                    prepared.email(), prepared.loginId(), settings.initialPasswordLink(prepared.raw()));
                case REJECTION -> dispatch.sender().sendRejection(prepared.email(), prepared.reason());
            }
        });
        return new ResendResult(prepared.id(), prepared.kind().name(), status.name());
    }

    private void check(MerchantApplication a, Long expected) {
        check(a, expected, false);
    }

    private void check(MerchantApplication a, Long expected, boolean emailReserved) {
        var d = query.latest(a);
        if (d == null || !d.getDeliveryId().equals(expected))
            throw new ApplicationException(ApplicationErrorCode.RESEND_CONFLICT);
        var availability = query.availability(a, query.now(), !emailReserved);
        // 이메일 제한은 이 요청이 이미 예약했지만 상태·PENDING은 최종 잠금에서 다시 검사한다.
        if (!availability.allowed()) {
            if (availability.nextResendAt() != null && !"IN_PROGRESS".equals(availability.blockedReason()))
                throw new ApplicationResendLimitException(availability.nextResendAt());
            throw new ApplicationException(ApplicationErrorCode.RESEND_CONFLICT);
        }
    }

    private void requireActor(SessionUser actor, HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        sessions.requireActive(actor.userId(), session.getId());
        var user = users.findById(actor.userId())
                .orElseThrow(() -> new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED));
        if (user.getStatus() != UserStatus.ACTIVE)
            throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        if (user.getRole() != UserRole.ADMIN) throw new AccountManagementException(AuthErrorCode.ACCESS_DENIED);
        if (user.isPasswordChangeRequired())
            throw new AccountManagementException(AuthErrorCode.PASSWORD_CHANGE_REQUIRED);
    }

    private record Prepared(
            Long id, ApplicationMailKind kind, String email, String loginId, String raw, String reason) {
        @Override
        public String toString() {
            return "Prepared[redacted]";
        }
    }
}
