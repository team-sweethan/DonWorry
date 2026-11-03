package com.donworry.fds.api;

import java.time.Instant;
import java.util.List;

public record FdsRuleChangeRequestPage(List<Item> content, int page, int size, long totalElements, int totalPages) {
    public record Item(
            Long requestId,
            String ruleCode,
            Long requestedByUserId,
            String requestedByName,
            String reason,
            Instant requestedAt,
            String status,
            Long reviewedByUserId,
            String reviewedByName,
            Instant reviewedAt) {}
}
