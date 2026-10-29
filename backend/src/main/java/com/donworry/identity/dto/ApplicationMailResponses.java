package com.donworry.identity.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

public final class ApplicationMailResponses {
    private ApplicationMailResponses() {}

    @Builder
    public record MailItem(
            Long deliveryId,
            String kind,
            String storedStatus,
            String effectiveStatus,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String failureCode,
            LocalDateTime expiresAt,
            LocalDateTime usedAt,
            LocalDateTime revokedAt) {}

    @Builder
    public record MailPage(List<MailItem> items, int page, int size, long totalElements) {}

    public record ResendResult(Long deliveryId, String kind, String mailStatus) {}

    public record Availability(String blockedReason, LocalDateTime nextResendAt) {
        public boolean allowed() {
            return blockedReason == null;
        }
    }
}
