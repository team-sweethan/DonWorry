package com.donworry.fds.api;

import java.time.Instant;
import java.util.List;

public record FdsRulePolicyChangeHistoryPage(
        List<Item> content, int page, int size, long totalElements, int totalPages) {
    public record Item(
            Long historyId,
            String ruleCode,
            String action,
            String source,
            String requestedByName,
            String requestReason,
            String changedByName,
            Instant changedAt) {}
}
