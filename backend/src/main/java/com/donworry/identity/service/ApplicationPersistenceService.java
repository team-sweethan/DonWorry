package com.donworry.identity.service;

import com.donworry.identity.dto.MerchantApplicationRequests.SubmitRequest;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import java.time.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationPersistenceService {
    private final MerchantApplicationRepository applications;
    private final ApplicationTokenRepository tokens;
    private final ApplicationMailDeliveryRepository deliveries;
    private final ApplicationTokenCodec codec;
    private final ApplicationRateLimitService limits;
    private final Clock identityClock;

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC);
    }

    @Transactional
    public PreparedApplicationMail submit(SubmitRequest input) {
        var app = applications.saveAndFlush(MerchantApplication.submit(
                input.merchantName(),
                input.businessRegistrationNumber(),
                input.contactName(),
                input.email(),
                input.phone(),
                input.inquiry(),
                input.consentVersion(),
                now()));
        limits.initialMail(input.email());
        return prepare(app);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<PreparedApplicationMail> resend(String email) {
        // 상태 인덱스를 먼저 잠그면 확인(기본키 → 상태 변경)과 순서가 엇갈려 교착이 난다 (#335).
        // id만 먼저 찾고 기본키로 잠근 뒤, 그사이 확인이 끝났을 수 있어 상태를 다시 본다.
        var found = applications
                .findUnverifiedIdByEmail(email)
                .flatMap(applications::lockById)
                .filter(app -> app.getStatus() == ApplicationStatus.EMAIL_PENDING);
        if (found.isEmpty() || !found.get().getCreatedAt().plusDays(7).isAfter(now())) return Optional.empty();
        return Optional.of(prepare(found.get()));
    }

    private PreparedApplicationMail prepare(MerchantApplication app) {
        var now = now();
        tokens.findByApplicationIdAndPurpose(app.getApplicationId(), ApplicationTokenPurpose.EMAIL_VERIFICATION)
                .forEach(t -> t.revoke(now));
        String raw = codec.generate();
        var token = tokens.saveAndFlush(
                ApplicationToken.emailVerification(app.getApplicationId(), ApplicationTokenCodec.hash(raw), now));
        var delivery = deliveries.saveAndFlush(
                ApplicationMailDelivery.verification(app.getApplicationId(), token.getTokenId(), now));
        return new PreparedApplicationMail(delivery.getDeliveryId(), app.getEmail(), raw);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void verify(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) fail(ApplicationErrorCode.INVALID_TOKEN);
        // 먼저 신청을 잠그고 토큰을 재조회하여 재발송과 동일한 잠금 순서를 유지한다.
        var token = tokens.findByTokenHash(ApplicationTokenCodec.hash(raw))
                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.INVALID_TOKEN));
        var app = applications
                .lockById(token.getApplicationId())
                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.INVALID_TOKEN));
        // 최초 조회 엔티티의 1차 캐시를 사용하지 않도록 명시적으로 갱신한다.
        token = refresh(token);
        var now = now();
        if (token.getPurpose() != ApplicationTokenPurpose.EMAIL_VERIFICATION) fail(ApplicationErrorCode.INVALID_TOKEN);
        if (token.getUsedAt() != null) fail(ApplicationErrorCode.USED_TOKEN);
        if (token.getRevokedAt() != null) fail(ApplicationErrorCode.REVOKED_TOKEN);
        if (!token.getExpiresAt().isAfter(now)
                || !app.getCreatedAt().plusDays(7).isAfter(now)) fail(ApplicationErrorCode.EXPIRED_TOKEN);
        if (app.getStatus() != ApplicationStatus.EMAIL_PENDING) fail(ApplicationErrorCode.USED_TOKEN);
        token.use(now);
        app.verifyEmail(now);
    }

    private final jakarta.persistence.EntityManager entityManager;

    private ApplicationToken refresh(ApplicationToken token) {
        entityManager.refresh(token, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return token;
    }

    @Transactional
    public void complete(Long id, ApplicationMailStatus status, String failureCode) {
        deliveries.findById(id).orElseThrow().complete(status, failureCode, now());
    }

    private void fail(ApplicationErrorCode code) {
        throw new ApplicationException(code);
    }
}
