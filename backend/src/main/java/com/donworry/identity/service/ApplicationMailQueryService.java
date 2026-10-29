package com.donworry.identity.service;

import com.donworry.identity.dto.ApplicationMailResponses.*;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class ApplicationMailQueryService {
    private final ApplicationMailDeliveryRepository deliveries;
    private final ApplicationTokenRepository tokens;
    private final MerchantApplicationRepository applications;
    private final PlatformUserRepository users;
    private final MerchantRepository merchants;
    private final ApplicationRateLimitService limits;
    private final ApplicationSettings settings;
    private final Clock identityClock;

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC);
    }

    public static ApplicationMailKind kind(ApplicationStatus status) {
        return switch (status) {
            case EMAIL_PENDING -> ApplicationMailKind.EMAIL_VERIFICATION;
            case APPROVED -> ApplicationMailKind.APPROVAL;
            case REJECTED -> ApplicationMailKind.REJECTION;
            default -> null;
        };
    }

    public ApplicationMailDelivery latest(MerchantApplication app) {
        var kind = kind(app.getStatus());
        return kind == null
                ? null
                : deliveries
                        .findFirstByApplicationIdAndKindOrderByDeliveryIdDesc(app.getApplicationId(), kind)
                        .orElse(null);
    }

    public MailItem item(ApplicationMailDelivery d, ApplicationToken t, LocalDateTime now) {
        if (d == null) return null;
        return MailItem.builder()
                .deliveryId(d.getDeliveryId())
                .kind(d.getKind().name())
                .storedStatus(d.getStatus().name())
                .effectiveStatus(effective(d, now))
                .startedAt(d.getStartedAt())
                .completedAt(d.getCompletedAt())
                .failureCode(d.getFailureCode())
                .expiresAt(t == null ? null : t.getExpiresAt())
                .usedAt(t == null ? null : t.getUsedAt())
                .revokedAt(t == null ? null : t.getRevokedAt())
                .build();
    }

    public String effective(ApplicationMailDelivery d, LocalDateTime now) {
        return d.getStatus() == ApplicationMailStatus.PENDING
                        && !d.getStartedAt().plusMinutes(5).isAfter(now)
                ? "UNKNOWN"
                : d.getStatus().name();
    }

    public Map<Long, MailItem> summaries(List<MerchantApplication> apps, LocalDateTime now) {
        if (apps.isEmpty()) return Map.of();
        var states = apps.stream().collect(Collectors.toMap(MerchantApplication::getApplicationId, a -> a.getStatus()));
        var rows = deliveries.findLatestForApplications(states.keySet());
        var tokenMap = tokenMap(rows);
        Map<Long, MailItem> result = new HashMap<>();
        for (var d : rows)
            if (d.getKind() == kind(states.get(d.getApplicationId())))
                result.put(d.getApplicationId(), item(d, tokenMap.get(d.getTokenId()), now));
        return result;
    }

    public MailItem latestItem(MerchantApplication a, LocalDateTime now) {
        var d = latest(a);
        return d == null
                ? null
                : item(
                        d,
                        d.getTokenId() == null
                                ? null
                                : tokens.findById(d.getTokenId()).orElse(null),
                        now);
    }

    private Map<Long, ApplicationToken> tokenMap(List<ApplicationMailDelivery> rows) {
        return tokens
                .findAllById(rows.stream()
                        .map(ApplicationMailDelivery::getTokenId)
                        .filter(Objects::nonNull)
                        .toList())
                .stream()
                .collect(Collectors.toMap(ApplicationToken::getTokenId, Function.identity()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MailPage history(Long id, int page, int size) {
        if (id == null || id <= 0 || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ApplicationException(ApplicationErrorCode.INVALID_INPUT);
        if (!applications.existsById(id)) throw new ApplicationException(ApplicationErrorCode.NOT_FOUND);
        var rows = deliveries.findByApplicationId(
                id, PageRequest.of(page, size, Sort.by("deliveryId").descending()));
        var tokenMap = tokenMap(rows.getContent());
        var now = now();
        return new MailPage(
                rows.stream()
                        .map(d -> item(d, tokenMap.get(d.getTokenId()), now))
                        .toList(),
                page,
                size,
                rows.getTotalElements());
    }

    public Availability availability(MerchantApplication a, LocalDateTime now) {
        return availability(a, now, true);
    }

    public Availability availability(MerchantApplication a, LocalDateTime now, boolean checkEmailLimit) {
        if (!settings.isEnabled()) return blocked("FEATURE_DISABLED");
        var kind = kind(a.getStatus());
        if (kind == null) return blocked("STATE_NOT_ELIGIBLE");
        if ((a.getStatus() == ApplicationStatus.EMAIL_PENDING
                        && !a.getCreatedAt().plusDays(7).isAfter(now))
                || (a.getStatus() == ApplicationStatus.REJECTED
                        && (a.getReviewedAt() == null
                                || !a.getReviewedAt().plusDays(30).isAfter(now)))) return blocked("RETENTION_EXPIRED");
        if (a.getStatus() == ApplicationStatus.APPROVED) {
            var user =
                    a.getUserId() == null ? null : users.findById(a.getUserId()).orElse(null);
            var merchant = a.getMerchantId() == null
                    ? null
                    : merchants.findById(a.getMerchantId()).orElse(null);
            if (user == null
                    || merchant == null
                    || !Objects.equals(user.getMerchantId(), a.getMerchantId())
                    || user.getRole() != UserRole.OPERATOR
                    || user.getStatus() != UserStatus.ACTIVE
                    || merchant.getStatus() != MerchantStatus.ACTIVE) return blocked("ACCOUNT_UNAVAILABLE");
            var setups = tokens.findByApplicationIdAndPurpose(
                    a.getApplicationId(), ApplicationTokenPurpose.INITIAL_PASSWORD_SETUP);
            if (!user.isPasswordChangeRequired() || setups.stream().anyMatch(t -> t.getUsedAt() != null))
                return blocked("SETUP_COMPLETED");
            var latest = setups.stream()
                    .max(Comparator.comparing(ApplicationToken::getTokenId))
                    .orElse(null);
            if (latest == null || !Objects.equals(latest.getUserId(), a.getUserId()))
                return blocked("HISTORY_UNAVAILABLE");
            if (latest.getRevokedAt() != null) return blocked("SETUP_REVOKED");
        }
        var rows = deliveries.findByApplicationIdAndKindOrderByDeliveryIdDesc(a.getApplicationId(), kind);
        if (rows.isEmpty()) return blocked("HISTORY_UNAVAILABLE");
        var latest = rows.getFirst();
        LocalDateTime retry = null;
        String reason = null;
        if (latest.getStatus() == ApplicationMailStatus.PENDING
                && latest.getStartedAt().plusMinutes(5).isAfter(now)) {
            retry = latest.getStartedAt().plusMinutes(5);
            reason = "IN_PROGRESS";
        }
        if (kind == ApplicationMailKind.EMAIL_VERIFICATION) {
            var emailRetry = checkEmailLimit ? limits.adminRetryAt(a.getEmail()) : null;
            if (emailRetry != null && (retry == null || emailRetry.isAfter(retry))) {
                retry = emailRetry;
                reason = "COOLDOWN";
            }
        } else {
            var cooldown = rows.stream()
                    .map(d -> d.getStartedAt().plusMinutes(1))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            if (cooldown.isAfter(now) && (retry == null || cooldown.isAfter(retry))) {
                retry = cooldown;
                reason = "COOLDOWN";
            }
            Long initial = rows.getLast().getDeliveryId();
            var recent = rows.stream()
                    .filter(d -> !d.getDeliveryId().equals(initial)
                            && d.getStartedAt().isAfter(now.minusHours(24)))
                    .toList();
            if (recent.size() >= 5) {
                var daily = recent.stream()
                        .map(d -> d.getStartedAt().plusHours(24))
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
                if (retry == null || daily.isAfter(retry)) {
                    retry = daily;
                    reason = "DAILY_LIMIT";
                }
            }
        }
        return new Availability(reason, retry);
    }

    private Availability blocked(String reason) {
        return new Availability(reason, null);
    }
}
