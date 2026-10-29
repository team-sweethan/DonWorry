package com.donworry.fds.dto;

import java.time.Instant;
import java.util.List;

public record FdsBlocklistPageResponse(List<Item> content, int page, int size, long totalElements, int totalPages) {
    public record Item(
            Long entryId,
            String identifierType,
            String identifierValue,
            String displayLabel,
            String reason,
            boolean active,
            Instant createdAt,
            Instant expiresAt) {}
}
