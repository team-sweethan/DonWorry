package com.donworry.identity.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record IntegrationResponse(
        String merchantCode,
        String status,
        long keyVersion,
        LocalDateTime keyIssuedAt,
        LocalDateTime connectedAt,
        List<History> histories,
        long totalElements) {
    @Builder
    public record History(
            Long historyId, Long actorUserId, String action, long keyVersion, String reason, LocalDateTime createdAt) {}
}
