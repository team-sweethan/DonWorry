package com.donworry.identity.dto;

import com.donworry.identity.entity.LoginHistory;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.Builder;

@Builder
public record LoginHistoryResponse(
        Long historyId, String eventType, Instant occurredAt, String clientIp, String userAgent, String logoutScope) {
    public static LoginHistoryResponse from(LoginHistory history) {
        return LoginHistoryResponse.builder()
                .historyId(history.getHistoryId())
                .eventType(history.getEventType().name())
                .occurredAt(history.getOccurredAt().toInstant(ZoneOffset.UTC))
                .clientIp(history.getClientIp())
                .userAgent(history.getUserAgent())
                .logoutScope(
                        history.getLogoutScope() == null
                                ? null
                                : history.getLogoutScope().name())
                .build();
    }
}
