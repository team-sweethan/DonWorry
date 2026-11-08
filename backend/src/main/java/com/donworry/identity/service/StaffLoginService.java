package com.donworry.identity.service;

import com.donworry.identity.api.StaffLogin;
import com.donworry.identity.api.StaffLoginEvent;
import com.donworry.identity.api.StaffLoginEventType;
import com.donworry.identity.api.StaffLoginReader;
import com.donworry.identity.entity.LoginEventType;
import com.donworry.identity.entity.LoginHistory;
import com.donworry.identity.repository.LoginHistoryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 직원 로그인 기록 읽기 (#643) — login_histories를 바꾸지 않고 읽기만 한다. 시각은 UTC로 저장돼 있다
@Service
@RequiredArgsConstructor
public class StaffLoginService implements StaffLoginReader {
    private final LoginHistoryRepository histories;

    @Override
    @Transactional(readOnly = true)
    public Optional<StaffLogin> findLogin(UUID loginReference) {
        if (loginReference == null) return Optional.empty();
        return histories
                .findByLoginReferenceAndEventType(loginReference, LoginEventType.LOGIN_SUCCESS)
                .map(StaffLoginService::toLogin);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffLogin> findSuccessfulLogins(Long staffId, Instant from, Instant to) {
        return histories
                .findByUserIdAndEventTypeAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscHistoryIdAsc(
                        staffId,
                        LoginEventType.LOGIN_SUCCESS,
                        LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                        LocalDateTime.ofInstant(to, ZoneOffset.UTC))
                .stream()
                .map(StaffLoginService::toLogin)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffLoginEvent> findLoginEvents(Long staffId, Instant from, Instant to) {
        return histories
                .findByUserIdAndOccurredAtBetweenOrderByOccurredAtAscHistoryIdAsc(
                        staffId,
                        LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                        LocalDateTime.ofInstant(to, ZoneOffset.UTC))
                .stream()
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

    private static StaffLogin toLogin(LoginHistory history) {
        return StaffLogin.builder()
                .staffId(history.getUserId())
                .loginReference(history.getLoginReference())
                .occurredAt(history.getOccurredAt().toInstant(ZoneOffset.UTC))
                .device(UserAgentDevice.label(history.getUserAgent()))
                .build();
    }
}
