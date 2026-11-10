package com.donworry.identity.service;

import com.donworry.identity.api.LoginAttackCriteria;
import com.donworry.identity.api.LoginTrafficCount;
import com.donworry.identity.api.LoginTrafficMinute;
import com.donworry.identity.api.LoginTrafficReader;
import com.donworry.identity.api.StaffLoginEvent;
import com.donworry.identity.api.StaffLoginEventType;
import com.donworry.identity.entity.LoginEventType;
import com.donworry.identity.repository.LoginHistoryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 로그인 보안 대시보드 읽기 (#770) — login_histories를 읽기만 한다. 시각은 UTC로 저장돼 있다
@Service
@RequiredArgsConstructor
public class LoginTrafficService implements LoginTrafficReader {
    private static final List<LoginEventType> ATTEMPTS =
            List.of(LoginEventType.LOGIN_SUCCESS, LoginEventType.LOGIN_FAILURE);
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LoginHistoryRepository histories;
    private final LoginAttackCheck attackCheck;

    @Override
    public LoginAttackCriteria criteria() {
        return attackCheck.criteria();
    }

    @Override
    @Transactional(readOnly = true)
    public LoginTrafficCount count(Instant from, Instant to) {
        LocalDateTime start = utc(from);
        LocalDateTime end = utc(to);
        return new LoginTrafficCount(
                histories.countByEventTypeInAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(ATTEMPTS, start, end),
                histories.countByEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
                        LoginEventType.LOGIN_FAILURE, start, end),
                histories.countByUserIdIsNullAndEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
                        LoginEventType.LOGIN_FAILURE, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoginTrafficMinute> countPerMinute(Instant from, Instant to) {
        return histories.countPerMinute(utc(from).format(SQL_TIME), utc(to).format(SQL_TIME)).stream()
                .map(row -> LoginTrafficMinute.builder()
                        .minuteStart(LocalDateTime.parse(row.getMinuteStart(), MINUTE)
                                .toInstant(ZoneOffset.UTC))
                        .attempts(row.getAttempts())
                        .failures(row.getFailures())
                        .unknownAccountFailures(row.getUnknownAccountFailures())
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffLoginEvent> findSecurityStaffLogins(Instant from, Instant to, int limit) {
        return histories.findSecurityStaffLogins(utc(from), utc(to), PageRequest.of(0, limit)).stream()
                .map(history -> StaffLoginEvent.builder()
                        .staffId(history.getUserId())
                        .eventType(StaffLoginEventType.valueOf(
                                history.getEventType().name()))
                        .occurredAt(history.getOccurredAt().toInstant(ZoneOffset.UTC))
                        .clientIp(history.getClientIp())
                        .device(UserAgentDevice.label(history.getUserAgent()))
                        .build())
                .toList();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
