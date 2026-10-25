package com.donworry.identity.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record LoginHistoryPageResponse(
        List<LoginHistoryResponse> items, int page, int size, long totalElements, int totalPages) {}
