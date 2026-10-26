package com.donworry.identity.service;

import com.donworry.identity.dto.*;
import com.donworry.identity.entity.LoginEventType;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.LoginHistoryRepository;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.security.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginHistoryQueryService {
    private final LoginHistoryRepository histories;
    private final PlatformUserRepository users;
    private final Clock clock;

    public LoginHistoryQueryService(
            LoginHistoryRepository histories, PlatformUserRepository users, @Qualifier("identityClock") Clock clock) {
        this.histories = histories;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LoginHistoryPageResponse own(SessionUser user, int page, int size) {
        requireUser(user);
        return page(user.userId(), page, size);
    }

    @Transactional(readOnly = true)
    public LoginHistoryPageResponse account(SessionUser actor, Long userId, int page, int size) {
        requireUser(actor);
        if (actor.role() != UserRole.ADMIN) throw new AccountManagementException(AuthErrorCode.ACCESS_DENIED);
        if (userId == null || userId <= 0) throw new AccountManagementException(AuthErrorCode.INVALID_HISTORY_INPUT);
        if (!users.existsById(userId)) throw new AccountManagementException(AuthErrorCode.ADMIN_USER_NOT_FOUND);
        return page(userId, page, size);
    }

    @Transactional(readOnly = true)
    public LatestLoginResponse latest(SessionUser user) {
        requireUser(user);
        var now = now();
        return new LatestLoginResponse(histories
                .findFirstByUserIdAndEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqualOrderByOccurredAtDescHistoryIdDesc(
                        user.userId(), LoginEventType.LOGIN_SUCCESS, now.minusDays(90), now)
                .map(LoginHistoryResponse::from)
                .orElse(null));
    }

    private LoginHistoryPageResponse page(Long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new AccountManagementException(AuthErrorCode.INVALID_HISTORY_INPUT);
        var now = now();
        var result = histories.findByUserIdAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
                userId,
                now.minusDays(90),
                now,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("historyId"))));
        return new LoginHistoryPageResponse(
                result.getContent().stream().map(LoginHistoryResponse::from).toList(),
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private void requireUser(SessionUser user) {
        if (user == null) throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        if (user.passwordChangeRequired()) throw new AccountManagementException(AuthErrorCode.PASSWORD_CHANGE_REQUIRED);
    }
}
